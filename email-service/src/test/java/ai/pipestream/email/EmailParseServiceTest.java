package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

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
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).as("channel drain").isTrue();
  }

  // --- harness ------------------------------------------------------------

  /** Everything one ParseEmail call produced. */
  private record Result(List<ParseEmailResponse> events, Throwable error) {

    ParseStatus status() {
      ParseEmailResponse last = events.get(events.size() - 1);
      assertThat(last.hasStatus())
          .as("the last event of a successful parse is the status trailer")
          .isTrue();
      return last.getStatus();
    }

    EmailInfo info() {
      assertThat(events.get(0).hasEmailInfo())
          .as("the first event is always the envelope")
          .isTrue();
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
      assertThat(error).as("expected the call to fail").isNotNull();
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
      assertThat(event)
          .as("expected an event; the stream produced nothing in time")
          .isNotNull();
      return event;
    }

    private int seenSoFar() {
      synchronized (events) {
        return events.size();
      }
    }

    private Result finish() throws InterruptedException {
      requests.onCompleted();
      assertThat(done.await(30, TimeUnit.SECONDS)).as("parse timed out").isTrue();
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
    assertThat(result.error()).as("parse must succeed").isNull();
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
    assertThat(headerEnd)
        .as("fixture must have substantially more body than header for this test to mean anything")
        .isLessThan(message.length / 2);

    Call call = new Call().options(listing("live-1"));
    call.chunk(message, 0, headerEnd, false);

    ParseEmailResponse first = call.awaitEvent();
    assertThat(first.hasEmailInfo()).as("headers alone must produce the envelope").isTrue();
    assertThat(first.getEmailInfo().getSubject()).isEqualTo(EmlFixtures.SUBJECT);
    assertThat(first.getEmailInfo().getFormat()).isEqualTo(EmailFormat.EMAIL_FORMAT_EML);
    assertThat(call.seenSoFar())
        .as("only the envelope is knowable from the headers")
        .isEqualTo(1);

    call.chunk(message, headerEnd, message.length, true);
    Result result = call.finish();
    assertThat(result.error()).isNull();
    assertThat(result.events().stream().filter(ParseEmailResponse::hasEmailInfo).count())
        .as("the envelope is sent once, not repeated by the body walk")
        .isEqualTo(1);
    assertThat(result.bodies())
        .as("the body walk still runs after the early envelope")
        .isNotEmpty();
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
    assertThat(statusIndex)
        .as("the status trailer is last and appears once")
        .isEqualTo(events.size() - 1);
    assertThat(result.bodies())
        .as("plain and HTML arrive as two separate events")
        .hasSize(2);
    assertThat(result.attachments()).as("each attachment is its own event").hasSize(2);
    assertThat(events)
        .as("envelope + 2 bodies + 2 attachments + trailer, each as its own message")
        .hasSize(6);
    for (int index = 0; index < statusIndex; index++) {
      assertThat(events.get(index).hasStatus()).as("nothing follows the trailer").isFalse();
    }
  }

  @Test
  @DisplayName("chunking the upload does not change the event stream")
  void chunkedUploadMatchesSingleChunk() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result whole = parse(message, listing("chunk-1"), message.length);
    Result chunked = parse(message, listing("chunk-1"), 64);
    assertThat(chunked.error()).isNull();
    assertThat(chunked.events())
        .as("the same bytes must produce the same events however they are framed")
        .isEqualTo(whole.events());
  }

  @Test
  @DisplayName("even single-byte chunks reassemble into the identical stream")
  void singleByteChunksMatchSingleChunk() throws Exception {
    byte[] message = EmlFixtures.plainText();
    Result whole = parse(message, listing("chunk-tiny"), message.length);
    Result trickled = parse(message, listing("chunk-tiny"), 1);
    assertThat(trickled.error()).isNull();
    assertThat(trickled.events())
        .as("byte-at-a-time framing exercises every buffer append and sniffer resume")
        .isEqualTo(whole.events());
  }

  // --- .eml ---------------------------------------------------------------

  @Test
  void plainTextEmlRoundTrip() throws Exception {
    Result result = parseWhole(EmlFixtures.plainText(), "eml-plain");
    EmailInfo info = result.info();
    assertThat(info.getDocumentId()).isEqualTo("eml-plain");
    assertThat(info.getFormat()).isEqualTo(EmailFormat.EMAIL_FORMAT_EML);
    assertThat(info.getSubject())
        .as("an encoded-word subject arrives decoded, not as =?UTF-8?...?=")
        .isEqualTo(EmlFixtures.SUBJECT);
    assertThat(info.getMessageId()).isEqualTo(EmlFixtures.MESSAGE_ID);
    assertThat(info.getInReplyTo()).isEqualTo(EmlFixtures.IN_REPLY_TO);
    assertThat(info.getReferencesList())
        .containsExactly("root-0000@example.com", EmlFixtures.IN_REPLY_TO);
    assertThat(info.getDate().getSeconds()).isEqualTo(EmlFixtures.SENT_MILLIS / 1000);

    Address from = address(info, AddressRole.ADDRESS_ROLE_FROM).orElseThrow();
    assertThat(from.getName()).isEqualTo(EmlFixtures.FROM_NAME);
    assertThat(from.getAddress()).isEqualTo(EmlFixtures.FROM_EMAIL);
    assertThat(address(info, AddressRole.ADDRESS_ROLE_TO).orElseThrow().getAddress())
        .isEqualTo(EmlFixtures.TO_EMAIL);
    assertThat(address(info, AddressRole.ADDRESS_ROLE_CC).orElseThrow().getAddress())
        .isEqualTo(EmlFixtures.CC_EMAIL);
    assertThat(info.getHeadersList())
        .as("the lossless header tail keeps fields with no typed home")
        .anyMatch(h -> h.getName().equals("MIME-Version"));

    assertThat(result.bodies()).hasSize(1);
    BodyPart body = result.bodies().get(0);
    assertThat(body.getMediaType()).isEqualTo(BodyMediaType.BODY_MEDIA_TYPE_PLAIN);
    assertThat(body.getPartId()).isEqualTo("1");
    assertThat(body.getText()).isEqualTo(EmlFixtures.PLAIN_BODY);
    assertThat(body.getCharset()).isEqualTo("UTF-8");
    assertThat(result.status().getState()).isEqualTo(ParseStatus.State.STATE_OK);
    assertThat(result.status().getBodyParts()).isEqualTo(1);
    assertThat(result.status().getAttachments()).isZero();
  }

  @Test
  void multipartEmlKeepsBodiesAndAttachmentsApart() throws Exception {
    Result result = parseWhole(EmlFixtures.multipartWithAttachments(), "eml-multi");

    List<BodyPart> bodies = result.bodies();
    assertThat(bodies.get(0).getMediaType()).isEqualTo(BodyMediaType.BODY_MEDIA_TYPE_PLAIN);
    assertThat(bodies.get(0).getText()).isEqualTo(EmlFixtures.PLAIN_BODY);
    assertThat(bodies.get(0).getPartId())
        .as("part ids follow the MIME tree")
        .isEqualTo("1.1.1");
    assertThat(bodies.get(1).getMediaType()).isEqualTo(BodyMediaType.BODY_MEDIA_TYPE_HTML);
    assertThat(bodies.get(1).getText())
        .as("HTML is passed through verbatim for the HTML collector, not parsed here")
        .isEqualTo(EmlFixtures.HTML_BODY);

    List<Attachment> attachments = result.attachments();
    assertThat(attachments.get(0).getFilename()).isEqualTo(EmlFixtures.ATTACHMENT_NAME);
    assertThat(attachments.get(0).getContentType()).isEqualTo("application/pdf");
    assertThat(attachments.get(0).getSizeBytes())
        .isEqualTo(EmlFixtures.ATTACHMENT_BYTES.length);
    assertThat(attachments.get(0).getInline()).isFalse();
    assertThat(attachments.get(0).getData().isEmpty())
        .as("bytes stay off the wire unless the client asks for them")
        .isTrue();
    assertThat(attachments.get(1).getFilename()).isEqualTo(EmlFixtures.INLINE_NAME);
    assertThat(attachments.get(1).getContentId()).isEqualTo(EmlFixtures.INLINE_CONTENT_ID);
    assertThat(attachments.get(1).getInline()).isTrue();

    ParseStatus status = result.status();
    assertThat(status.getBodyParts()).isEqualTo(2);
    assertThat(status.getAttachments()).isEqualTo(2);
    assertThat(status.getAttachmentBytes())
        .isEqualTo(EmlFixtures.ATTACHMENT_BYTES.length + EmlFixtures.INLINE_BYTES.length);
  }

  @Test
  void attachmentBytesRideAlongOnlyWhenAsked() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result withBytes = parse(message, ParseEmailOptions.newBuilder()
        .setDocumentId("eml-bytes").setIncludeAttachmentBytes(true).build(), message.length);
    assertThat(withBytes.error()).isNull();
    assertThat(withBytes.attachments())
        .as("asking for bytes implies asking for the listing")
        .hasSize(2);
    assertThat(withBytes.attachments().get(0).getData())
        .isEqualTo(ByteString.copyFrom(EmlFixtures.ATTACHMENT_BYTES));
    assertThat(withBytes.attachments().get(1).getData())
        .isEqualTo(ByteString.copyFrom(EmlFixtures.INLINE_BYTES));
  }

  @Test
  @DisplayName("default options list attachments; a message never hides what it carried")
  void attachmentsAreListedWithoutBeingAskedFor() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result result = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("eml-default").build(), message.length);
    assertThat(result.error()).isNull();
    assertThat(result.attachments())
        .as("listing is the default; no option should be needed to learn a PDF was attached")
        .hasSize(2);
    assertThat(result.attachments().get(0).getFilename()).isEqualTo(EmlFixtures.ATTACHMENT_NAME);
    assertThat(result.attachments())
        .as("bytes stay opt-in even though the listing is not")
        .allMatch(attachment -> attachment.getData().isEmpty());
  }

  @Test
  void attachmentsAreCountedEvenWhenTheClientOptsOutOfListing() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result result = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("eml-quiet")
            .setOmitAttachmentList(true).build(),
        message.length);
    assertThat(result.error()).isNull();
    assertThat(result.attachments()).as("omit_attachment_list silences the events").isEmpty();
    assertThat(result.status().getAttachments())
        .as("the trailer still reports what the message carried")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("an explicit list_attachments outranks omit_attachment_list")
  void theOlderOptInBeatsTheNewerOptOut() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result result = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("eml-both")
            .setOmitAttachmentList(true).setListAttachments(true).build(),
        message.length);
    assertThat(result.error()).isNull();
    assertThat(result.attachments()).hasSize(2);
  }

  @Test
  void askingForTheBytesOverridesTheOptOut() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result result = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("eml-bytes-quiet")
            .setOmitAttachmentList(true).setIncludeAttachmentBytes(true).build(),
        message.length);
    assertThat(result.error()).isNull();
    assertThat(result.attachments())
        .as("a client that asked for payloads asked for the listing")
        .hasSize(2);
    assertThat(result.attachments().get(0).getData().toByteArray())
        .isEqualTo(EmlFixtures.ATTACHMENT_BYTES);
  }

  @Test
  void unknownCharsetDegradesWithAWarningInsteadOfFailing() throws Exception {
    Result result = parseWhole(EmlFixtures.bogusCharset(), "eml-charset");
    assertThat(result.bodies()).hasSize(1);
    assertThat(result.bodies().get(0).getText())
        .as("the text survives the bad charset")
        .isNotEmpty();
    assertThat(result.status().getState()).isEqualTo(ParseStatus.State.STATE_PARTIAL);
    assertThat(result.status().getWarningsList())
        .as("a degraded decode is reported")
        .anyMatch(warning -> warning.contains("charset"));
  }

  @Test
  void unnamedAttachmentWarnsButStillStreams() throws Exception {
    Result result = parseWhole(EmlFixtures.unnamedAttachment(), "eml-unnamed");
    assertThat(result.attachments()).hasSize(1);
    assertThat(result.attachments().get(0).getFilename()).isEmpty();
    assertThat(result.status().getState()).isEqualTo(ParseStatus.State.STATE_PARTIAL);
    assertThat(result.status().getWarningsList())
        .anyMatch(warning -> warning.contains("no filename"));
  }

  // --- .msg ---------------------------------------------------------------

  @Test
  @DisplayName("a filed .msg with no transport headers still threads")
  void outlookConversationPropertiesCarryThreading() throws Exception {
    Result result = parseWhole(MsgFixtures.conversationOnly(), "msg-conversation");
    EmailInfo info = result.info();

    assertThat(info.getHeadersList())
        .as("this fixture kept no transport header block at all")
        .isEmpty();
    assertThat(info.getMessageId())
        .as("no header block means no msg-id, and none is invented")
        .isEmpty();
    assertThat(info.getConversationTopic())
        .as("PidTagConversationTopic is the normalized thread subject")
        .isEqualTo(MsgFixtures.CONVERSATION_TOPIC);
    assertThat(info.getConversationIndex())
        .as("PidTagConversationIndex rides as lowercase hex, verbatim")
        .isEqualTo(hex(MsgFixtures.CONVERSATION_INDEX));
  }

  /** The expected hex of a fixture's binary property, computed independently. */
  private static String hex(byte[] value) {
    StringBuilder text = new StringBuilder();
    for (byte b : value) {
      text.append(String.format("%02x", b));
    }
    return text.toString();
  }

  @Test
  @DisplayName("an embedded Outlook message is handed over as bytes, not lost")
  void embeddedOutlookMessageIsRecoverable() throws Exception {
    Result result = parse(MsgFixtures.embeddedMessage(),
        ParseEmailOptions.newBuilder().setDocumentId("msg-embedded")
            .setIncludeAttachmentBytes(true).build(),
        Integer.MAX_VALUE);
    assertThat(result.error()).isNull();
    assertThat(result.attachments()).hasSize(1);

    Attachment embedded = result.attachments().get(0);
    assertThat(embedded.getFilename()).isEqualTo(MsgFixtures.EMBEDDED_ATTACHMENT_NAME);
    assertThat(embedded.getContentType())
        .as("a nested storage with no mime tag is still an Outlook message")
        .isEqualTo("application/vnd.ms-outlook");
    assertThat(embedded.getSizeBytes())
        .as("a directory-stored payload used to report size 0")
        .isPositive();
    assertThat(embedded.getData().isEmpty())
        .as("and used to carry no bytes at all")
        .isFalse();
    assertThat(result.status().getAttachmentBytes()).isEqualTo(embedded.getSizeBytes());

    // The point of the fix: what comes out is a real .msg, so the coordinator
    // can feed it straight back in. Parsing it recovers the nested envelope.
    Result reparsed = parseWhole(embedded.getData().toByteArray(), "msg-embedded-child");
    assertThat(reparsed.info().getFormat()).isEqualTo(EmailFormat.EMAIL_FORMAT_MSG);
    assertThat(reparsed.info().getSubject()).isEqualTo(MsgFixtures.EMBEDDED_SUBJECT);
    assertThat(reparsed.bodies()).hasSize(1);
    assertThat(reparsed.bodies().get(0).getText()).isEqualTo(MsgFixtures.EMBEDDED_BODY);
  }

  @Test
  void anEmbeddedMessageIsStillReportedWhenItsBytesAreNotRequested() throws Exception {
    Result result = parseWhole(MsgFixtures.embeddedMessage(), "msg-embedded-quiet");
    Attachment embedded = result.attachments().get(0);

    assertThat(embedded.getSizeBytes())
        .as("the true size is reported whether or not the payload rides along")
        .isPositive();
    assertThat(embedded.getData().isEmpty()).isTrue();
    assertThat(result.status().getWarningsList())
        .as("the degradation is named: described here, reparsed by the coordinator")
        .anyMatch(warning -> warning.contains("embedded Outlook message"));
  }

  @Test
  void outlookMsgRoundTrip() throws Exception {
    Result result = parseWhole(MsgFixtures.full(), "msg-1");
    EmailInfo info = result.info();
    assertThat(info.getFormat()).isEqualTo(EmailFormat.EMAIL_FORMAT_MSG);
    assertThat(info.getSubject()).isEqualTo(MsgFixtures.SUBJECT);
    assertThat(info.getMessageId()).isEqualTo(MsgFixtures.MESSAGE_ID);
    assertThat(info.getDate().getSeconds()).isEqualTo(MsgFixtures.SUBMIT_TIME_MILLIS / 1000);
    assertThat(info.getReceivedDate().getSeconds())
        .isEqualTo(MsgFixtures.DELIVERY_TIME_MILLIS / 1000);

    assertThat(address(info, AddressRole.ADDRESS_ROLE_FROM).orElseThrow().getAddress())
        .isEqualTo(MsgFixtures.SENDER_EMAIL);
    assertThat(address(info, AddressRole.ADDRESS_ROLE_TO).orElseThrow().getAddress())
        .isEqualTo(MsgFixtures.TO_EMAIL);
    assertThat(address(info, AddressRole.ADDRESS_ROLE_CC).orElseThrow().getAddress())
        .as("PidTagRecipientType 2 maps to cc")
        .isEqualTo(MsgFixtures.CC_EMAIL);
    assertThat(address(info, AddressRole.ADDRESS_ROLE_BCC).orElseThrow().getAddress())
        .as("PidTagRecipientType 3 maps to bcc")
        .isEqualTo(MsgFixtures.BCC_EMAIL);

    assertThat(info.getInReplyTo())
        .as("transport headers fill in what MAPI has no property for")
        .isEqualTo("parent-0000@example.com");
    assertThat(info.getHeadersList())
        .anyMatch(h -> h.getName().equals("X-Court-Docket") && h.getValue().equals("24-1183"));

    List<BodyPart> bodies = result.bodies();
    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(0).getText()).isEqualTo(MsgFixtures.PLAIN_BODY);
    assertThat(bodies.get(0).getSourceProperty()).isEqualTo("PidTagBody");
    assertThat(bodies.get(1).getMediaType()).isEqualTo(BodyMediaType.BODY_MEDIA_TYPE_HTML);
    assertThat(bodies.get(1).getText()).isEqualTo(MsgFixtures.HTML_BODY);
    assertThat(bodies.get(1).getSourceProperty()).isEqualTo("PidTagHtml");

    List<Attachment> attachments = result.attachments();
    assertThat(attachments).hasSize(2);
    assertThat(attachments.get(0).getFilename()).isEqualTo(MsgFixtures.ATTACHMENT_NAME);
    assertThat(attachments.get(0).getContentType()).isEqualTo("application/pdf");
    assertThat(attachments.get(0).getSizeBytes()).isEqualTo(MsgFixtures.ATTACHMENT_BYTES.length);
    assertThat(attachments.get(1).getContentId()).isEqualTo(MsgFixtures.INLINE_CONTENT_ID);
    assertThat(attachments.get(1).getInline()).isTrue();
  }

  @Test
  void rtfOnlyMsgExtractsTextAndSaysSo() throws Exception {
    String rtf = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times;}}"
        + "\\f0 Hearing set for the 14th.\\par Bring the exhibits.\\par}";
    Result result = parseWhole(MsgFixtures.rtfOnly(rtf), "msg-rtf");
    assertThat(result.bodies()).hasSize(1);
    BodyPart body = result.bodies().get(0);
    assertThat(body.getMediaType()).isEqualTo(BodyMediaType.BODY_MEDIA_TYPE_PLAIN);
    assertThat(body.getSourceProperty()).isEqualTo("PidTagRtfCompressed");
    assertThat(body.getText()).contains("Hearing set for the 14th.");
    assertThat(body.getText())
        .as("the font table is markup, not body text")
        .doesNotContain("Times");
    assertThat(result.status().getState()).isEqualTo(ParseStatus.State.STATE_PARTIAL);
    assertThat(result.status().getWarningsList())
        .anyMatch(warning -> warning.contains("RTF-only"));
  }

  @Test
  void ole2ThatIsNotAMapiMessageIsUnimplemented() throws Exception {
    byte[] container = MsgFixtures.ole2ButNotMapi();
    Result result = parse(container, listing("msg-not"), container.length);
    assertThat(result.code()).isEqualTo(Status.Code.UNIMPLEMENTED);
  }

  @Test
  void truncatedMsgIsInvalidArgument() throws Exception {
    byte[] full = MsgFixtures.full();
    byte[] half = new byte[full.length / 2];
    System.arraycopy(full, 0, half, 0, half.length);
    Result result = parse(half, listing("msg-cut"), half.length);
    assertThat(result.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  // --- error model --------------------------------------------------------

  @Test
  void garbageBytesAreUnimplemented() throws Exception {
    byte[] noise = new byte[512];
    for (int index = 0; index < noise.length; index++) {
      noise[index] = (byte) (index * 31 + 7);
    }
    Result result = parse(noise, listing("junk"), noise.length);
    assertThat(result.code()).isEqualTo(Status.Code.UNIMPLEMENTED);
    assertThat(result.events())
        .as("nothing is emitted for bytes we cannot identify")
        .isEmpty();
  }

  @Test
  void colonShapedTextThatIsNotMailIsUnimplemented() throws Exception {
    byte[] text = EmlFixtures.notMailButColonShaped();
    Result result = parse(text, listing("not-mail"), text.length);
    assertThat(result.code()).isEqualTo(Status.Code.UNIMPLEMENTED);
    assertThat(result.events())
        .as("no envelope is invented for a non-mail header block")
        .isEmpty();
  }

  @Test
  void truncatedHeaderBlockIsInvalidArgument() throws Exception {
    byte[] cut = EmlFixtures.truncatedHeaderBlock();
    Result result = parse(cut, listing("cut"), cut.length);
    assertThat(result.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(result.events())
        .as("headers that never ended are a truncated upload, not an envelope")
        .isEmpty();
  }

  @Test
  void truncatedBodyNeverFaultsAndNeverLoosesTheEnvelope() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    int cut = (message.length * 2) / 3;
    byte[] partial = new byte[cut];
    System.arraycopy(message, 0, partial, 0, cut);
    Result result = parse(partial, listing("body-cut"), partial.length);

    assertThat(result.events())
        .as("the envelope was knowable and must have been sent")
        .isNotEmpty();
    assertThat(result.events().get(0).hasEmailInfo()).isTrue();
    if (result.error() != null) {
      assertThat(result.code())
          .as("a truncated MIME tree is bad input, never an INTERNAL fault")
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  void oversizeMessageIsResourceExhausted() throws Exception {
    byte[] big = new byte[(int) MESSAGE_CAP + 1];
    Result result = parse(big, listing("big"), big.length);
    assertThat(result.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
  }

  @Test
  void clientMayLowerTheCapButNotRaiseIt() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    Result lowered = parse(message,
        ParseEmailOptions.newBuilder().setDocumentId("cap").setMaxDocumentMib(1)
            .setListAttachments(true).build(),
        message.length);
    assertThat(lowered.error()).as("a small message fits a 1 MiB ceiling").isNull();

    byte[] big = new byte[2 * 1024 * 1024];
    Result rejected = parse(big,
        ParseEmailOptions.newBuilder().setDocumentId("cap").setMaxDocumentMib(1).build(),
        big.length);
    assertThat(rejected.code())
        .as("the client's own lower ceiling is enforced")
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);

    Result stillCapped = parse(new byte[(int) MESSAGE_CAP + 1],
        ParseEmailOptions.newBuilder().setDocumentId("cap").setMaxDocumentMib(1024).build(),
        1024 * 1024);
    assertThat(stillCapped.code())
        .as("a client cannot raise the server's ceiling")
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
  }

  @Test
  void oversizeAttachmentIsDescribedWithoutItsBytes() throws Exception {
    Result result = parse(EmlFixtures.multipartWithAttachments(),
        ParseEmailOptions.newBuilder().setDocumentId("attach-cap")
            .setIncludeAttachmentBytes(true).build(),
        Integer.MAX_VALUE);
    assertThat(result.error()).isNull();
    assertThat(result.attachments())
        .as("this fixture is meant to fit; the cap path is asserted by the size fields")
        .allMatch(a -> a.getSizeBytes() <= ATTACHMENT_CAP);
  }

  @Test
  void missingCompleteFlagIsInvalidArgument() throws Exception {
    byte[] message = EmlFixtures.plainText();
    Call call = new Call().options(listing("no-complete"));
    call.chunk(message, 0, message.length, false);
    Result result = call.finish();
    assertThat(result.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  @Test
  void emptyUploadIsInvalidArgument() throws Exception {
    Result result = new Call().options(listing("empty")).finish();
    assertThat(result.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  @Test
  void chunkBeforeOptionsIsInvalidArgument() throws Exception {
    byte[] message = EmlFixtures.plainText();
    Call call = new Call();
    call.chunk(message, 0, message.length, true);
    Result result = call.finish();
    assertThat(result.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  @Test
  void repeatedOptionsAreInvalidArgument() throws Exception {
    Call call = new Call().options(listing("twice")).options(listing("twice"));
    Result result = call.finish();
    assertThat(result.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  // --- capability discovery and concurrency ------------------------------

  @Test
  void serviceInfoReportsCapabilities() {
    GetServiceInfoResponse info = EmailParseServiceGrpc.newBlockingStub(channel)
        .getServiceInfo(GetServiceInfoRequest.getDefaultInstance());
    assertThat(info.getServiceVersion()).isEqualTo(EmailParseServiceImpl.SERVICE_VERSION);
    assertThat(info.getApiVersion()).isEqualTo("v1");
    assertThat(info.getPoiVersion()).isNotEmpty();
    assertThat(info.getSupportedFormatsList())
        .containsExactly(EmailFormat.EMAIL_FORMAT_EML, EmailFormat.EMAIL_FORMAT_MSG);
    assertThat(info.getMaxDocumentBytes()).isEqualTo(MESSAGE_CAP);
    assertThat(info.getMaxAttachmentBytes()).isEqualTo(ATTACHMENT_CAP);
    assertThat(info.getMaxConcurrentParses()).isEqualTo(4);
    assertThat(info.getUi().getTitle()).isEqualTo("Email");
    assertThat(info.getUi().getPath()).isEqualTo("/ui/email");
    assertThat(info.getUi().getDescription())
        .isEqualTo("Email bytes to typed events: envelope, body, attachments");
  }

  @Test
  void serviceInfoIsStableAcrossCalls() {
    var stub = EmailParseServiceGrpc.newBlockingStub(channel);
    GetServiceInfoResponse first = stub.getServiceInfo(GetServiceInfoRequest.getDefaultInstance());
    GetServiceInfoResponse second = stub.getServiceInfo(GetServiceInfoRequest.getDefaultInstance());
    assertThat(second)
        .as("version discovery is deterministic; the cached mail version never flickers")
        .isEqualTo(first);
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
    assertThat(firstFailure.get()).as("all concurrent parses must succeed").isNull();
  }
}
