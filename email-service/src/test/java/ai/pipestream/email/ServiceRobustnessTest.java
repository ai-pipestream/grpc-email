package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.server.EmailParseServiceImpl;
import ai.pipestream.email.v1.EmailChunk;
import ai.pipestream.email.v1.ParseEmailOptions;
import ai.pipestream.email.v1.ParseEmailRequest;
import ai.pipestream.email.v1.ParseEmailResponse;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
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
 * What the server does when a parse goes wrong in ways the parsers cannot
 * report themselves: every call must still end with a status, and the
 * parse slot it held must come back.
 */
class ServiceRobustnessTest {

  private static final long MESSAGE_CAP = 4L * 1024 * 1024;
  private static final long ATTACHMENT_CAP = 64 * 1024;

  private static ExecutorService executor;

  @BeforeAll
  static void start() {
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterAll
  static void stop() {
    executor.shutdown();
  }

  /** The outcome of one call: how it ended, if it ended. */
  private static final class Outcome implements StreamObserver<ParseEmailResponse> {
    private final Error thrownOnBody;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean completed;

    private Outcome(Error thrownOnBody) {
      this.thrownOnBody = thrownOnBody;
    }

    @Override
    public void onNext(ParseEmailResponse event) {
      if (thrownOnBody != null && event.hasBodyPart()) {
        // Body parts are written from the parse thread, so this lands the
        // error exactly where a parser's own would.
        throw thrownOnBody;
      }
    }

    @Override
    public void onError(Throwable error) {
      failure.set(error);
      done.countDown();
    }

    @Override
    public void onCompleted() {
      completed = true;
      done.countDown();
    }

    private Outcome await() throws InterruptedException {
      assertThat(done.await(30, TimeUnit.SECONDS))
          .as("the call must end with a status, not hang until the client's deadline")
          .isTrue();
      return this;
    }

    private Status.Code code() {
      assertThat(failure.get()).as("expected the call to fail").isNotNull();
      return Status.fromThrowable(failure.get()).getCode();
    }
  }

  /** Drives one whole call straight into the service, no transport in between. */
  private static Outcome drive(EmailParseServiceImpl service, byte[] bytes, Error thrownOnBody)
      throws InterruptedException {
    Outcome outcome = new Outcome(thrownOnBody);
    StreamObserver<ParseEmailRequest> requests = service.parseEmail(outcome);
    requests.onNext(ParseEmailRequest.newBuilder()
        .setOptions(ParseEmailOptions.newBuilder().setDocumentId("robustness"))
        .build());
    requests.onNext(ParseEmailRequest.newBuilder()
        .setChunk(EmailChunk.newBuilder().setData(ByteString.copyFrom(bytes)).setComplete(true))
        .build());
    requests.onCompleted();
    return outcome.await();
  }

  /** One parse slot, so a slot that leaks blocks the next call outright. */
  private static EmailParseServiceImpl singleSlotService() {
    return new EmailParseServiceImpl(MESSAGE_CAP, ATTACHMENT_CAP, 1, executor);
  }

  @Test
  @DisplayName("a StackOverflowError on the parse thread ends the call as INVALID_ARGUMENT")
  void stackOverflowEndsTheCall() throws Exception {
    EmailParseServiceImpl service = singleSlotService();
    Outcome outcome = drive(service, EmlFixtures.plainText(), new StackOverflowError());
    assertThat(outcome.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(drive(service, EmlFixtures.plainText(), null).completed)
        .as("the slot came back: the next parse on a one-slot server completes")
        .isTrue();
  }

  @Test
  @DisplayName("an OutOfMemoryError on the parse thread ends the call as RESOURCE_EXHAUSTED")
  void outOfMemoryEndsTheCall() throws Exception {
    EmailParseServiceImpl service = singleSlotService();
    Outcome outcome = drive(service, EmlFixtures.plainText(), new OutOfMemoryError("test"));
    assertThat(outcome.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(drive(service, EmlFixtures.plainText(), null).completed).isTrue();
  }

  @Test
  @DisplayName("any other Error on the parse thread ends the call as INTERNAL and is counted")
  void anyOtherErrorEndsTheCall() throws Exception {
    EmailParseServiceImpl service = singleSlotService();
    Outcome outcome = drive(service, EmlFixtures.plainText(), new LinkageError("test"));
    assertThat(outcome.code()).isEqualTo(Status.Code.INTERNAL);
    assertThat(service.metrics().render()).contains("failed=1");
    assertThat(drive(service, EmlFixtures.plainText(), null).completed).isTrue();
  }
}
