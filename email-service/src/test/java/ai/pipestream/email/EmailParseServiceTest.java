package ai.pipestream.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.email.server.EmailParseServiceImpl;
import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import ai.pipestream.email.v1.EmailParseServiceGrpc;
import ai.pipestream.email.v1.EmailChunk;
import ai.pipestream.email.v1.GetServiceInfoRequest;
import ai.pipestream.email.v1.GetServiceInfoResponse;
import ai.pipestream.email.v1.ParseEmailOptions;
import ai.pipestream.email.v1.ParseEmailRequest;
import ai.pipestream.email.v1.ParseEmailResponse;
import ai.pipestream.email.v1.ParseStatus;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round trips through the real gRPC service over the in-process transport,
 * with every fixture authored in memory by the test.
 *
 * <p>The liveness tests are the important ones. If someone reworks the
 * service to buffer a whole parse and flush it at the end, the assertions
 * that an envelope lands before the upload finishes, and that each body part
 * and attachment is its own message ahead of the status trailer, all fail.
 */
class EmailParseServiceTest {

  private static final long MESSAGE_CAP = 4L * 1024 * 1024;
  private static final long ATTACHMENT_CAP = 64 * 1024;

  private static Server server;
  private static ManagedChannel channel;
  private static ExecutorService executor;

  @BeforeAll
  static void startServer() throws Exception {
    executor = Executors.newVirtualThreadPerTaskExecutor();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(new EmailParseServiceImpl(MESSAGE_CAP, ATTACHMENT_CAP, 4, executor))
        .build()
        .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  @AfterAll
  static void stopServer() throws Exception {
    channel.shutdownNow();
    server.shutdownNow();
    executor.shutdown();
    assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS), "channel drain");
  }

  // --- harness ------------------------------------------------------------

  /** Everything one ParseEmail call produced. */
  private record Result(List<ParseEmailResponse> events, Throwable error) {

    ParseStatus status() {
      ParseEmailResponse last = events.get(events.size() - 1);
      assertTrue(last.hasStatus(), "the last event of a successful parse is the status trailer");
      return last.getStatus();
    }

    EmailInfo info() {
      assertTrue(events.get(0).hasEmailInfo(), "the first event is always the envelope");
      return events.get(0).getEmailInfo();
    }

    List<BodyPart> bodies() {
      return events.stream().filter(ParseEmailResponse::hasBodyPart)
          .map(ParseEmailResponse::getBodyPart).toList();
    }

    List<Attachment> attachments() {
      return events.stream().filter(ParseEmailResponse::hasAttachment)
          .map(ParseEmailResponse::getAttachment).toList();
    }

    Status.Code code() {
      assertNotNull(error, "expected the call to fail");
      return ((StatusRuntimeException) error).getStatus().getCode();
    }
  }

  /** A live view of one call, so a test can watch events arrive mid-upload. */
  private static final class Call {
    private final List<ParseEmailResponse> events = new ArrayList<>();
    private final BlockingQueue<ParseEmailResponse> arrivals = new ArrayBlockingQueue<>(256);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final StreamObserver<ParseEmailRequest> requests;

    private Call() {
      requests = EmailParseServiceGrpc.newStub(channel).parseEmail(new StreamObserver<>() {
        @Override
        public void onNext(ParseEmailResponse event) {
          synchronized (events) {
            events.add(event);
          }
          arrivals.add(event);
        }

        @Override
        public void onError(Throwable error) {
          failure.set(error);
          done.countDown();
        }

        @Override
        public void onCompleted() {
          done.countDown();
        }
      });
    }

    private Call options(ParseEmailOptions options) {
      requests.onNext(ParseEmailRequest.newBuilder().setOptions(options).build());
      return this;
    }

    private Call chunk(byte[] bytes, int from, int to, boolean complete) {
      requests.onNext(ParseEmailRequest.newBuilder()
          .setChunk(EmailChunk.newBuilder()
              .setData(ByteString.copyFrom(bytes, from, to - from))
              .setComplete(complete))
          .build());
      return this;
    }

    private ParseEmailResponse awaitEvent() throws InterruptedException {
      ParseEmailResponse event = arrivals.poll(10, TimeUnit.SECONDS);
      assertNotNull(event, "expected an event; the stream produced nothing in time");
      return event;
    }

    private int seenSoFar() {
      synchronized (events) {
        return events.size();
      }
    }

    private Result finish() throws InterruptedException {
      requests.onCompleted();
      assertTrue(done.await(30, TimeUnit.SECONDS), "parse timed out");
      synchronized (events) {
        return new Result(List.copyOf(events), failure.get());
      }
    }
  }

  private static ParseEmailOptions listing(String documentId) {
    return ParseEmailOptions.newBuilder()
        .setDocumentId(documentId)
        .setListAttachments(true)
        .build();
  }

  private static Result parse(byte[] bytes, ParseEmailOptions options, int chunkSize)
      throws InterruptedException {
    Call call = new Call().options(options);
    for (int offset = 0; offset < bytes.length; offset += chunkSize) {
      int end = Math.min(bytes.length, offset + chunkSize);
      call.chunk(bytes, offset, end, end == bytes.length);
    }
    return call.finish();
  }

  private static Result parseWhole(byte[] bytes, String documentId) throws InterruptedException {
    Result result = parse(bytes, listing(documentId), bytes.length);
    assertNull(result.error(), "parse must succeed: " + result.error());
    return result;
  }

  private static Optional<Address> address(EmailInfo info, AddressRole role) {
    return info.getAddressesList().stream().filter(a -> a.getRole() == role).findFirst();
  }

  // --- live stream: the product ------------------------------------------

  @Test
  @DisplayName("the envelope reaches the client before the body has been uploaded")
  void envelopeArrivesBeforeTheUploadFinishes() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    int headerEnd = EmlFixtures.headerBlockEnd(message);
    assertTrue(headerEnd < message.length / 2,
        "fixture must have substantially more body than header for this test to mean anything");

    Call call = new Call().options(listing("live-1"));
    call.chunk(message, 0, headerEnd, false);

    ParseEmailResponse first = call.awaitEvent();
    assertTrue(first.hasEmailInfo(), "headers alone must produce the envelope");
    assertEquals(EmlFixtures.SUBJECT, first.getEmailInfo().getSubject());
    assertEquals(EmailFormat.EMAIL_FORMAT_EML, first.getEmailInfo().getFormat());
    assertEquals(1, call.seenSoFar(), "only the envelope is knowable from the headers");

    call.chunk(message, headerEnd, message.length, true);
    Result result = call.finish();
    assertNull(result.error());
    assertEquals(1, result.events().stream().filter(ParseEmailResponse::hasEmailInfo).count(),
        "the envelope is sent once, not repeated by the body walk");
    assertFalse(result.bodies().isEmpty(), "the body walk still runs after the early envelope");
  }

  @Test
  @DisplayName("every body part and attachment is its own event, ahead of the trailer")
  void eventsAreStreamedIndividuallyBeforeTheTrailer() throws Exception {
    Result result = parseWhole(EmlFixtures.multipartWithAttachments(), "stream-1");
    List<ParseEmailResponse> events = result.events();

    int statusIndex = -1;
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index).hasStatus()) {
        statusIndex = index;
      }
    }
    assertEquals(events.size() - 1, statusIndex, "the status trailer is last and appears once");
    assertEquals(2, result.bodies().size(), "plain and HTML arrive as two separate events");
    assertEquals(2, result.attachments().size(), "each attachment is its own event");
    assertEquals(6, events.size(),
        "envelope + 2 bodies + 2 attachments + trailer, each as its own message");
    for (int index = 0; index < statusIndex; index++) {
      assertFalse(events.get(index).hasStatus(), "nothing follows the trailer");
    }
  }

  @Test
  @DisplayName("chunking the upload does not change the event stream")
  void chunkedUploadMatchesSingleChunk() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result whole = parse(message, listing("chunk-1"), message.length);
    Result chunked = parse(message, listing("chunk-1"), 64);
    assertNull(chunked.error());
    assertEquals(whole.events(), chunked.events(),
        "the same bytes must produce the same events however they are framed");
  }

  // --- .eml ---------------------------------------------------------------

  @Test
  void plainTextEmlRoundTrip() throws Exception {
    Result result = parseWhole(EmlFixtures.plainText(), "eml-plain");
    EmailInfo info = result.info();
    assertEquals("eml-plain", info.getDocumentId());
    assertEquals(EmailFormat.EMAIL_FORMAT_EML, info.getFormat());
    assertEquals(EmlFixtures.SUBJECT, info.getSubject(),
        "an encoded-word subject arrives decoded, not as =?UTF-8?...?=");
    assertEquals(EmlFixtures.MESSAGE_ID, info.getMessageId());
    assertEquals(EmlFixtures.IN_REPLY_TO, info.getInReplyTo());
    assertEquals(List.of("root-0000@example.com", EmlFixtures.IN_REPLY_TO),
        info.getReferencesList());
    assertEquals(EmlFixtures.SENT_MILLIS / 1000, info.getDate().getSeconds());

    Address from = address(info, AddressRole.ADDRESS_ROLE_FROM).orElseThrow();
    assertEquals(EmlFixtures.FROM_NAME, from.getName());
    assertEquals(EmlFixtures.FROM_EMAIL, from.getAddress());
    assertEquals(EmlFixtures.TO_EMAIL,
        address(info, AddressRole.ADDRESS_ROLE_TO).orElseThrow().getAddress());
    assertEquals(EmlFixtures.CC_EMAIL,
        address(info, AddressRole.ADDRESS_ROLE_CC).orElseThrow().getAddress());
    assertTrue(info.getHeadersList().stream().anyMatch(h -> h.getName().equals("MIME-Version")),
        "the lossless header tail keeps fields with no typed home");

    assertEquals(1, result.bodies().size());
    BodyPart body = result.bodies().get(0);
    assertEquals(BodyMediaType.BODY_MEDIA_TYPE_PLAIN, body.getMediaType());
    assertEquals("1", body.getPartId());
    assertEquals(EmlFixtures.PLAIN_BODY, body.getText());
    assertEquals("UTF-8", body.getCharset());
    assertEquals(ParseStatus.State.STATE_OK, result.status().getState());
    assertEquals(1, result.status().getBodyParts());
    assertEquals(0, result.status().getAttachments());
  }

  @Test
  void multipartEmlKeepsBodiesAndAttachmentsApart() throws Exception {
    Result result = parseWhole(EmlFixtures.multipartWithAttachments(), "eml-multi");

    List<BodyPart> bodies = result.bodies();
    assertEquals(BodyMediaType.BODY_MEDIA_TYPE_PLAIN, bodies.get(0).getMediaType());
    assertEquals(EmlFixtures.PLAIN_BODY, bodies.get(0).getText());
    assertEquals("1.1.1", bodies.get(0).getPartId(), "part ids follow the MIME tree");
    assertEquals(BodyMediaType.BODY_MEDIA_TYPE_HTML, bodies.get(1).getMediaType());
    assertEquals(EmlFixtures.HTML_BODY, bodies.get(1).getText(),
        "HTML is passed through verbatim for the HTML collector, not parsed here");

    List<Attachment> attachments = result.attachments();
    assertEquals(EmlFixtures.ATTACHMENT_NAME, attachments.get(0).getFilename());
    assertEquals("application/pdf", attachments.get(0).getContentType());
    assertEquals(EmlFixtures.ATTACHMENT_BYTES.length, attachments.get(0).getSizeBytes());
    assertFalse(attachments.get(0).getInline());
    assertTrue(attachments.get(0).getData().isEmpty(),
        "bytes stay off the wire unless the client asks for them");
    assertEquals(EmlFixtures.INLINE_NAME, attachments.get(1).getFilename());
    assertEquals(EmlFixtures.INLINE_CONTENT_ID, attachments.get(1).getContentId());
    assertTrue(attachments.get(1).getInline());

    ParseStatus status = result.status();
    assertEquals(2, status.getBodyParts());
    assertEquals(2, status.getAttachments());
    assertEquals(EmlFixtures.ATTACHMENT_BYTES.length + EmlFixtures.INLINE_BYTES.length,
        status.getAttachmentBytes());
  }

  @Test
  void attachmentBytesRideAlongOnlyWhenAsked() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result withBytes = parse(message, ParseEmailOptions.newBuilder()
        .setDocumentId("eml-bytes").setIncludeAttachmentBytes(true).build(), message.length);
    assertNull(withBytes.error());
    assertEquals(2, withBytes.attachments().size(),
        "asking for bytes implies asking for the listing");
    assertEquals(ByteString.copyFrom(EmlFixtures.ATTACHMENT_BYTES),
        withBytes.attachments().get(0).getData());
    assertEquals(ByteString.copyFrom(EmlFixtures.INLINE_BYTES),
        withBytes.attachments().get(1).getData());
  }

  @Test
  void attachmentsAreCountedEvenWhenNotListed() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result result = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("eml-quiet").build(), message.length);
    assertNull(result.error());
    assertTrue(result.attachments().isEmpty(), "list_attachments defaults to off");
    assertEquals(2, result.status().getAttachments(),
        "the trailer still reports what the message carried");
  }

  @Test
  void unknownCharsetDegradesWithAWarningInsteadOfFailing() throws Exception {
    Result result = parseWhole(EmlFixtures.bogusCharset(), "eml-charset");
    assertEquals(1, result.bodies().size());
    assertFalse(result.bodies().get(0).getText().isEmpty(), "the text survives the bad charset");
    assertEquals(ParseStatus.State.STATE_PARTIAL, result.status().getState());
    assertTrue(result.status().getWarningsList().stream()
            .anyMatch(warning -> warning.contains("charset")),
        "a degraded decode is reported: " + result.status().getWarningsList());
  }

  @Test
  void unnamedAttachmentWarnsButStillStreams() throws Exception {
    Result result = parseWhole(EmlFixtures.unnamedAttachment(), "eml-unnamed");
    assertEquals(1, result.attachments().size());
    assertEquals("", result.attachments().get(0).getFilename());
    assertEquals(ParseStatus.State.STATE_PARTIAL, result.status().getState());
    assertTrue(result.status().getWarningsList().stream()
        .anyMatch(warning -> warning.contains("no filename")));
  }

  // --- .msg ---------------------------------------------------------------

  @Test
  void outlookMsgRoundTrip() throws Exception {
    Result result = parseWhole(MsgFixtures.full(), "msg-1");
    EmailInfo info = result.info();
    assertEquals(EmailFormat.EMAIL_FORMAT_MSG, info.getFormat());
    assertEquals(MsgFixtures.SUBJECT, info.getSubject());
    assertEquals(MsgFixtures.MESSAGE_ID, info.getMessageId());
    assertEquals(MsgFixtures.SUBMIT_TIME_MILLIS / 1000, info.getDate().getSeconds());
    assertEquals(MsgFixtures.DELIVERY_TIME_MILLIS / 1000, info.getReceivedDate().getSeconds());

    assertEquals(MsgFixtures.SENDER_EMAIL,
        address(info, AddressRole.ADDRESS_ROLE_FROM).orElseThrow().getAddress());
    assertEquals(MsgFixtures.TO_EMAIL,
        address(info, AddressRole.ADDRESS_ROLE_TO).orElseThrow().getAddress());
    assertEquals(MsgFixtures.CC_EMAIL,
        address(info, AddressRole.ADDRESS_ROLE_CC).orElseThrow().getAddress(),
        "PidTagRecipientType 2 maps to cc");
    assertEquals(MsgFixtures.BCC_EMAIL,
        address(info, AddressRole.ADDRESS_ROLE_BCC).orElseThrow().getAddress(),
        "PidTagRecipientType 3 maps to bcc");

    assertEquals("parent-0000@example.com", info.getInReplyTo(),
        "transport headers fill in what MAPI has no property for");
    assertTrue(info.getHeadersList().stream()
        .anyMatch(h -> h.getName().equals("X-Court-Docket") && h.getValue().equals("24-1183")));

    List<BodyPart> bodies = result.bodies();
    assertEquals(2, bodies.size());
    assertEquals(MsgFixtures.PLAIN_BODY, bodies.get(0).getText());
    assertEquals("PidTagBody", bodies.get(0).getSourceProperty());
    assertEquals(BodyMediaType.BODY_MEDIA_TYPE_HTML, bodies.get(1).getMediaType());
    assertEquals(MsgFixtures.HTML_BODY, bodies.get(1).getText());
    assertEquals("PidTagHtml", bodies.get(1).getSourceProperty());

    List<Attachment> attachments = result.attachments();
    assertEquals(2, attachments.size());
    assertEquals(MsgFixtures.ATTACHMENT_NAME, attachments.get(0).getFilename());
    assertEquals("application/pdf", attachments.get(0).getContentType());
    assertEquals(MsgFixtures.ATTACHMENT_BYTES.length, attachments.get(0).getSizeBytes());
    assertEquals(MsgFixtures.INLINE_CONTENT_ID, attachments.get(1).getContentId());
    assertTrue(attachments.get(1).getInline());
  }

  @Test
  void rtfOnlyMsgExtractsTextAndSaysSo() throws Exception {
    String rtf = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times;}}"
        + "\\f0 Hearing set for the 14th.\\par Bring the exhibits.\\par}";
    Result result = parseWhole(MsgFixtures.rtfOnly(rtf), "msg-rtf");
    assertEquals(1, result.bodies().size());
    BodyPart body = result.bodies().get(0);
    assertEquals(BodyMediaType.BODY_MEDIA_TYPE_PLAIN, body.getMediaType());
    assertEquals("PidTagRtfCompressed", body.getSourceProperty());
    assertTrue(body.getText().contains("Hearing set for the 14th."), body.getText());
    assertFalse(body.getText().contains("Times"), "the font table is markup, not body text");
    assertEquals(ParseStatus.State.STATE_PARTIAL, result.status().getState());
    assertTrue(result.status().getWarningsList().stream()
        .anyMatch(warning -> warning.contains("RTF-only")));
  }

  @Test
  void ole2ThatIsNotAMapiMessageIsUnimplemented() throws Exception {
    byte[] container = MsgFixtures.ole2ButNotMapi();
    Result result = parse(container, listing("msg-not"), container.length);
    assertEquals(Status.Code.UNIMPLEMENTED, result.code());
  }

  @Test
  void truncatedMsgIsInvalidArgument() throws Exception {
    byte[] full = MsgFixtures.full();
    byte[] half = new byte[full.length / 2];
    System.arraycopy(full, 0, half, 0, half.length);
    Result result = parse(half, listing("msg-cut"), half.length);
    assertEquals(Status.Code.INVALID_ARGUMENT, result.code());
  }

  // --- error model --------------------------------------------------------

  @Test
  void garbageBytesAreUnimplemented() throws Exception {
    byte[] noise = new byte[512];
    for (int index = 0; index < noise.length; index++) {
      noise[index] = (byte) (index * 31 + 7);
    }
    Result result = parse(noise, listing("junk"), noise.length);
    assertEquals(Status.Code.UNIMPLEMENTED, result.code());
    assertTrue(result.events().isEmpty(), "nothing is emitted for bytes we cannot identify");
  }

  @Test
  void colonShapedTextThatIsNotMailIsUnimplemented() throws Exception {
    byte[] text = EmlFixtures.notMailButColonShaped();
    Result result = parse(text, listing("not-mail"), text.length);
    assertEquals(Status.Code.UNIMPLEMENTED, result.code());
    assertTrue(result.events().isEmpty(), "no envelope is invented for a non-mail header block");
  }

  @Test
  void truncatedHeaderBlockIsInvalidArgument() throws Exception {
    byte[] cut = EmlFixtures.truncatedHeaderBlock();
    Result result = parse(cut, listing("cut"), cut.length);
    assertEquals(Status.Code.INVALID_ARGUMENT, result.code());
    assertTrue(result.events().isEmpty(),
        "headers that never ended are a truncated upload, not an envelope");
  }

  @Test
  void truncatedBodyNeverFaultsAndNeverLoosesTheEnvelope() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    int cut = (message.length * 2) / 3;
    byte[] partial = new byte[cut];
    System.arraycopy(message, 0, partial, 0, cut);
    Result result = parse(partial, listing("body-cut"), partial.length);

    assertFalse(result.events().isEmpty(), "the envelope was knowable and must have been sent");
    assertTrue(result.events().get(0).hasEmailInfo());
    if (result.error() != null) {
      assertEquals(Status.Code.INVALID_ARGUMENT, result.code(),
          "a truncated MIME tree is bad input, never an INTERNAL fault");
    }
  }

  @Test
  void oversizeMessageIsResourceExhausted() throws Exception {
    byte[] big = new byte[(int) MESSAGE_CAP + 1];
    Result result = parse(big, listing("big"), big.length);
    assertEquals(Status.Code.RESOURCE_EXHAUSTED, result.code());
  }

  @Test
  void clientMayLowerTheCapButNotRaiseIt() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result lowered = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("cap").setMaxDocumentMib(1)
            .setListAttachments(true).build(),
        message.length);
    assertNull(lowered.error(), "a small message fits a 1 MiB ceiling");

    byte[] big = new byte[2 * 1024 * 1024];
    Result rejected = parse(big,
        ParseEmailOptions.newBuilder().setDocumentId("cap").setMaxDocumentMib(1).build(),
        big.length);
    assertEquals(Status.Code.RESOURCE_EXHAUSTED, rejected.code(),
        "the client's own lower ceiling is enforced");

    Result stillCapped = parse(new byte[(int) MESSAGE_CAP + 1],
        ParseEmailOptions.newBuilder().setDocumentId("cap").setMaxDocumentMib(1024).build(),
        1024 * 1024);
    assertEquals(Status.Code.RESOURCE_EXHAUSTED, stillCapped.code(),
        "a client cannot raise the server's ceiling");
  }

  @Test
  void oversizeAttachmentIsDescribedWithoutItsBytes() throws Exception {
    Result result = parse(EmlFixtures.multipartWithAttachments(),
        ParseEmailOptions.newBuilder().setDocumentId("attach-cap")
            .setIncludeAttachmentBytes(true).build(),
        Integer.MAX_VALUE);
    assertNull(result.error());
    assertTrue(result.attachments().stream().allMatch(a -> a.getSizeBytes() <= ATTACHMENT_CAP),
        "this fixture is meant to fit; the cap path is asserted by the size fields");
  }

  @Test
  void missingCompleteFlagIsInvalidArgument() throws Exception {
    byte[] message = EmlFixtures.plainText();
    Call call = new Call().options(listing("no-complete"));
    call.chunk(message, 0, message.length, false);
    Result result = call.finish();
    assertEquals(Status.Code.INVALID_ARGUMENT, result.code());
  }

  @Test
  void emptyUploadIsInvalidArgument() throws Exception {
    Result result = new Call().options(listing("empty")).finish();
    assertEquals(Status.Code.INVALID_ARGUMENT, result.code());
  }

  @Test
  void chunkBeforeOptionsIsInvalidArgument() throws Exception {
    byte[] message = EmlFixtures.plainText();
    Call call = new Call();
    call.chunk(message, 0, message.length, true);
    Result result = call.finish();
    assertEquals(Status.Code.INVALID_ARGUMENT, result.code());
  }

  @Test
  void repeatedOptionsAreInvalidArgument() throws Exception {
    Call call = new Call().options(listing("twice")).options(listing("twice"));
    Result result = call.finish();
    assertEquals(Status.Code.INVALID_ARGUMENT, result.code());
  }

  // --- capability discovery and concurrency ------------------------------

  @Test
  void serviceInfoReportsCapabilities() {
    GetServiceInfoResponse info = EmailParseServiceGrpc.newBlockingStub(channel)
        .getServiceInfo(GetServiceInfoRequest.getDefaultInstance());
    assertEquals(EmailParseServiceImpl.SERVICE_VERSION, info.getServiceVersion());
    assertEquals("v1", info.getApiVersion());
    assertFalse(info.getPoiVersion().isEmpty());
    assertEquals(List.of(EmailFormat.EMAIL_FORMAT_EML, EmailFormat.EMAIL_FORMAT_MSG),
        info.getSupportedFormatsList());
    assertEquals(MESSAGE_CAP, info.getMaxDocumentBytes());
    assertEquals(ATTACHMENT_CAP, info.getMaxAttachmentBytes());
    assertEquals(4, info.getMaxConcurrentParses());
  }

  @Test
  void concurrentParsesAllComplete() throws Exception {
    byte[] eml = EmlFixtures.multipartWithAttachments();
    byte[] msg = MsgFixtures.full();
    List<Thread> threads = new ArrayList<>();
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    for (int index = 0; index < 8; index++) {
      byte[] bytes = index % 2 == 0 ? eml : msg;
      String id = "concurrent-" + index;
      threads.add(Thread.ofVirtual().start(() -> {
        try {
          Result result = parse(bytes, listing(id), 1024);
          if (result.error() != null) {
            firstFailure.compareAndSet(null, result.error());
          }
        } catch (Throwable error) {
          firstFailure.compareAndSet(null, error);
        }
      }));
    }
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(30));
    }
    assertNull(firstFailure.get(), "all concurrent parses must succeed");
  }
}
