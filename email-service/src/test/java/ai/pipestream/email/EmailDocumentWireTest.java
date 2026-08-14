package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.document.v1.BaseTextItem;
import ai.pipestream.document.v1.Document;
import ai.pipestream.document.v1.TextItemBase;
import ai.pipestream.email.document.EmailDocumentFold;
import ai.pipestream.email.server.EmailParseServiceImpl;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailChunk;
import ai.pipestream.email.v1.EmailParseServiceGrpc;
import ai.pipestream.email.v1.ParseEmailOptions;
import ai.pipestream.email.v1.ParseEmailRequest;
import ai.pipestream.email.v1.ParseEmailResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
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
 * The Document projection over the real gRPC service.
 *
 * <p>These assertions are about the wire contract of the option: that the
 * document is one event, that it lands immediately before the trailer and
 * never after it, that it says the same things the typed events said, and
 * that a client which did not ask for it sees no trace of it.
 */
class EmailDocumentWireTest {

  private static Server server;
  private static ManagedChannel channel;
  private static ExecutorService executor;

  @BeforeAll
  static void startServer() throws Exception {
    executor = Executors.newVirtualThreadPerTaskExecutor();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(new EmailParseServiceImpl(4L * 1024 * 1024, 64 * 1024, 4, executor))
        .build()
        .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  @AfterAll
  static void stopServer() throws Exception {
    channel.shutdownNow();
    server.shutdownNow();
    executor.shutdown();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  // --- harness ------------------------------------------------------------

  /** Runs one whole parse and returns every event, in arrival order. */
  private static List<ParseEmailResponse> parse(byte[] message, boolean emitDocument)
      throws InterruptedException {
    List<ParseEmailResponse> events = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    StreamObserver<ParseEmailRequest> requests =
        EmailParseServiceGrpc.newStub(channel).parseEmail(new StreamObserver<>() {
          @Override
          public void onNext(ParseEmailResponse event) {
            events.add(event);
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
    requests.onNext(ParseEmailRequest.newBuilder()
        .setOptions(ParseEmailOptions.newBuilder()
            .setDocumentId("doc-fold")
            .setListAttachments(true)
            .setEmitDocument(emitDocument))
        .build());
    requests.onNext(ParseEmailRequest.newBuilder()
        .setChunk(EmailChunk.newBuilder().setData(ByteString.copyFrom(message)).setComplete(true))
        .build());
    requests.onCompleted();
    assertThat(done.await(30, TimeUnit.SECONDS)).as("parse timed out").isTrue();
    assertThat(failure.get()).as("parse must succeed").isNull();
    return List.copyOf(events);
  }

  private static Document onlyDocument(List<ParseEmailResponse> events) {
    List<ParseEmailResponse> documents =
        events.stream().filter(ParseEmailResponse::hasDocument).toList();
    assertThat(documents).as("the document is emitted exactly once").hasSize(1);
    assertThat(events.get(events.size() - 2).hasDocument())
        .as("the document is the event immediately before the trailer")
        .isTrue();
    assertThat(events.get(events.size() - 1).hasStatus())
        .as("the status trailer stays last")
        .isTrue();
    return documents.get(0).getDocument();
  }

  private static List<BodyPart> bodies(List<ParseEmailResponse> events) {
    return events.stream().filter(ParseEmailResponse::hasBodyPart)
        .map(ParseEmailResponse::getBodyPart).toList();
  }

  private static List<Attachment> attachments(List<ParseEmailResponse> events) {
    return events.stream().filter(ParseEmailResponse::hasAttachment)
        .map(ParseEmailResponse::getAttachment).toList();
  }

  private static TextItemBase base(BaseTextItem item) {
    return switch (item.getItemCase()) {
      case TITLE -> item.getTitle().getBase();
      case LIST_ITEM -> item.getListItem().getBase();
      case TEXT -> item.getText().getBase();
      default -> throw new AssertionError("unexpected text variant " + item.getItemCase());
    };
  }

  private static List<String> textsOf(Document document, BaseTextItem.ItemCase kind) {
    return document.getTextsList().stream()
        .filter(item -> item.getItemCase() == kind)
        .map(item -> base(item).getText())
        .toList();
  }

  /**
   * Asserts the projection against the typed events of the same stream: the
   * document may say less than the events, never something else.
   */
  private static void assertAgreesWithEvents(
      List<ParseEmailResponse> events, Document document, String model, String mimetype) {
    assertThat(EmailDocumentFold.integrityErrors(document)).isEmpty();

    ai.pipestream.email.v1.EmailInfo info = events.get(0).getEmailInfo();
    assertThat(document.getName()).isEqualTo(info.getSubject());
    assertThat(document.getOrigin().getMimetype()).isEqualTo(mimetype);
    assertThat(document.getOrigin().getFilename()).isEqualTo(info.getDocumentId());
    assertThat(textsOf(document, BaseTextItem.ItemCase.TITLE)).containsExactly(info.getSubject());

    List<String> plain = bodies(events).stream()
        .filter(part -> part.getMediaType() == BodyMediaType.BODY_MEDIA_TYPE_PLAIN)
        .map(BodyPart::getText)
        .map(String::strip)
        .toList();
    assertThat(textsOf(document, BaseTextItem.ItemCase.TEXT)).isEqualTo(plain);

    List<Attachment> listed = attachments(events);
    List<String> lines = textsOf(document, BaseTextItem.ItemCase.LIST_ITEM);
    assertThat(lines).hasSameSizeAs(listed);
    for (int index = 0; index < listed.size(); index++) {
      assertThat(lines.get(index))
          .contains(listed.get(index).getFilename())
          .contains(listed.get(index).getContentType())
          .contains(Long.toString(listed.get(index).getSizeBytes()));
    }

    List<String> inlineImages = listed.stream()
        .filter(attachment -> attachment.getInline()
            && attachment.getContentType().startsWith("image/")
            && !attachment.getContentId().isEmpty())
        .map(attachment -> "part:" + attachment.getPartId())
        .toList();
    assertThat(document.getPicturesList().stream()
        .map(picture -> picture.getImage().getUri()).toList())
        .isEqualTo(inlineImages);

    assertThat(document.getTextsList()).allSatisfy(item -> {
      assertThat(base(item).getSourceList()).hasSize(1);
      assertThat(base(item).getSource(0).getCollector().getCollector()).isEqualTo("email");
      assertThat(base(item).getSource(0).getCollector().getModel()).isEqualTo(model);
      assertThat(base(item).getSource(0).getCollector().getVersion())
          .isEqualTo(EmailParseServiceImpl.SERVICE_VERSION);
    });
  }

  // --- tests --------------------------------------------------------------

  @Test
  @DisplayName("an .eml parse yields one document, just before the trailer")
  void emlEmitsOneDocumentBeforeTheTrailer() throws Exception {
    List<ParseEmailResponse> events = parse(EmlFixtures.multipartWithAttachments(), true);
    Document document = onlyDocument(events);

    assertThat(events).hasSize(7);
    assertAgreesWithEvents(events, document, "eml", "message/rfc822");
    assertThat(document.getName()).isEqualTo(EmlFixtures.SUBJECT);
    assertThat(textsOf(document, BaseTextItem.ItemCase.TEXT))
        .containsExactly(EmlFixtures.PLAIN_BODY);
    assertThat(document.getPictures(0).getImage().getMimetype()).isEqualTo("image/png");
  }

  @Test
  @DisplayName("an Outlook .msg parse yields the same shape, stamped msg")
  void msgEmitsOneDocumentBeforeTheTrailer() throws Exception {
    List<ParseEmailResponse> events = parse(MsgFixtures.full(), true);
    Document document = onlyDocument(events);

    assertAgreesWithEvents(events, document, "msg", "application/vnd.ms-outlook");
    assertThat(document.getName()).isEqualTo(MsgFixtures.SUBJECT);
    assertThat(textsOf(document, BaseTextItem.ItemCase.TEXT))
        .containsExactly(MsgFixtures.PLAIN_BODY);
    assertThat(document.getPictures(0).getImage().getUri()).isEqualTo("part:attach:1");
  }

  @Test
  @DisplayName("the HTML body reaches the client but never the document")
  void htmlStaysOnTheTypedStreamOnly() throws Exception {
    List<ParseEmailResponse> events = parse(EmlFixtures.multipartWithAttachments(), true);
    assertThat(bodies(events)).anyMatch(
        part -> part.getMediaType() == BodyMediaType.BODY_MEDIA_TYPE_HTML);
    assertThat(onlyDocument(events).toString()).doesNotContain("<html>");
  }

  @Test
  void withoutTheOptionThereIsNoDocumentEventAtAll() throws Exception {
    for (byte[] message : List.of(EmlFixtures.multipartWithAttachments(), MsgFixtures.full())) {
      List<ParseEmailResponse> events = parse(message, false);
      assertThat(events).noneMatch(ParseEmailResponse::hasDocument);
      assertThat(events.get(events.size() - 1).hasStatus()).isTrue();
      assertThat(events).hasSize(6);
    }
  }

  @Test
  @DisplayName("asking for the document changes nothing else on the stream")
  void theTypedEventsAreUntouchedByTheOption() throws Exception {
    byte[] message = EmlFixtures.multipartWithAttachments();
    List<ParseEmailResponse> without = parse(message, false);
    List<ParseEmailResponse> with = parse(message, true).stream()
        .filter(event -> !event.hasDocument())
        .toList();
    assertThat(with).isEqualTo(without);
  }
}
