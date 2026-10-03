package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.server.EmailParseServiceImpl;
import ai.pipestream.email.v1.EmailChunk;
import ai.pipestream.email.v1.EmailParseServiceGrpc;
import ai.pipestream.email.v1.ParseEmailOptions;
import ai.pipestream.email.v1.ParseEmailRequest;
import ai.pipestream.email.v1.ParseEmailResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the server does when a call goes wrong in ways the parsers cannot
 * report themselves: an Error on the parse thread, a client that gives up, a
 * server with more callers than parse slots. Every call must still end, no
 * upload may be buffered before it holds a slot, and every slot must come
 * back. The servers here have one slot, so a slot taken too early or never
 * returned stalls the next call outright.
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

  // --- over the in-process transport: admission and cancellation ----------

  private Server server;
  private ManagedChannel channel;
  private EmailParseServiceImpl service;

  /** Starts a one-slot server behind a real (in-process) transport. */
  private void startOneSlotServer() throws Exception {
    service = singleSlotService();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(service)
        .build()
        .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  @AfterEach
  void stopServer() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
      server.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      channel = null;
    }
  }

  /** A live client call: what arrived, and how it ended. */
  private static final class Call implements StreamObserver<ParseEmailResponse> {
    private final List<ParseEmailResponse> events = new ArrayList<>();
    private final CountDownLatch firstEvent = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Consumer<Call> onBody;
    private volatile boolean completed;
    private ClientCallStreamObserver<ParseEmailRequest> requests;

    private Call(Consumer<Call> onBody) {
      this.onBody = onBody;
    }

    private static Call start(EmailParseServiceGrpc.EmailParseServiceStub stub) {
      return start(stub, call -> { });
    }

    private static Call start(
        EmailParseServiceGrpc.EmailParseServiceStub stub, Consumer<Call> onBody) {
      Call call = new Call(onBody);
      call.requests = (ClientCallStreamObserver<ParseEmailRequest>) stub.parseEmail(call);
      call.requests.onNext(ParseEmailRequest.newBuilder()
          .setOptions(ParseEmailOptions.newBuilder().setDocumentId("admission"))
          .build());
      return call;
    }

    private Call chunk(byte[] bytes, int from, int to, boolean complete) {
      requests.onNext(ParseEmailRequest.newBuilder()
          .setChunk(EmailChunk.newBuilder()
              .setData(ByteString.copyFrom(bytes, from, to - from))
              .setComplete(complete))
          .build());
      return this;
    }

    private Call finishUpload(byte[] bytes, int from) {
      chunk(bytes, from, bytes.length, true);
      requests.onCompleted();
      return this;
    }

    private void cancel() {
      requests.cancel("the client gave up", null);
    }

    private synchronized int eventCount() {
      return events.size();
    }

    @Override
    public void onNext(ParseEmailResponse event) {
      synchronized (this) {
        events.add(event);
      }
      firstEvent.countDown();
      if (event.hasBodyPart()) {
        onBody.accept(this);
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

    private Call awaitFirstEvent() throws InterruptedException {
      assertThat(firstEvent.await(10, TimeUnit.SECONDS)).as("an event arrived").isTrue();
      return this;
    }

    private Call await() throws InterruptedException {
      assertThat(done.await(30, TimeUnit.SECONDS)).as("the call ended").isTrue();
      return this;
    }

    private Status.Code code() {
      assertThat(failure.get()).as("expected the call to fail").isNotNull();
      return Status.fromThrowable(failure.get()).getCode();
    }
  }

  private EmailParseServiceGrpc.EmailParseServiceStub stub() {
    return EmailParseServiceGrpc.newStub(channel);
  }

  @Test
  @DisplayName("an upload is not read, let alone buffered, until it holds a parse slot")
  void aQueuedUploadIsNotReadBeforeItIsAdmitted() throws Exception {
    startOneSlotServer();
    byte[] message = EmlFixtures.multipartWithAttachments();
    int headerEnd = EmlFixtures.headerBlockEnd(message);

    // A holds the only slot, mid-upload: its envelope is out, its body is not.
    Call first = Call.start(stub()).chunk(message, 0, headerEnd, false).awaitFirstEvent();

    // B sends everything at once. Until A is done, none of it may be read:
    // reading B's header block is what would put B's envelope on the wire.
    Call second = Call.start(stub()).finishUpload(message, 0);
    Thread.sleep(300);
    assertThat(second.eventCount())
        .as("a call waiting for a slot has had none of its bytes read")
        .isZero();

    first.finishUpload(message, headerEnd).await();
    assertThat(first.completed).isTrue();
    second.await();
    assertThat(second.completed).as("admitted once the slot came back").isTrue();
    assertThat(second.events.get(0).hasEmailInfo()).isTrue();
    assertThat(second.events.get(second.events.size() - 1).hasStatus()).isTrue();
  }

  @Test
  @DisplayName("a call cancelled while waiting for a slot does not keep the one it gets")
  void aCallCancelledWhileQueuedGivesItsTurnAway() throws Exception {
    startOneSlotServer();
    byte[] message = EmlFixtures.plainText();
    int headerEnd = EmlFixtures.headerBlockEnd(message);

    Call first = Call.start(stub()).chunk(message, 0, headerEnd, false).awaitFirstEvent();
    Call queued = Call.start(stub()).finishUpload(message, 0);
    queued.cancel();
    queued.await();
    assertThat(queued.code()).isEqualTo(Status.Code.CANCELLED);

    first.finishUpload(message, headerEnd).await();
    assertThat(first.completed).isTrue();
    Call next = Call.start(stub()).finishUpload(message, 0).await();
    assertThat(next.completed)
        .as("the slot the cancelled call was handed went straight back")
        .isTrue();
  }

  @Test
  @DisplayName("cancelling mid-upload frees the slot for the next caller")
  void cancellingMidUploadFreesTheSlot() throws Exception {
    startOneSlotServer();
    byte[] message = EmlFixtures.plainText();
    int headerEnd = EmlFixtures.headerBlockEnd(message);

    Call abandoned = Call.start(stub()).chunk(message, 0, headerEnd, false).awaitFirstEvent();
    abandoned.cancel();
    abandoned.await();

    Call next = Call.start(stub()).finishUpload(message, 0).await();
    assertThat(next.completed).isTrue();
  }

  @Test
  @DisplayName("a client deadline that passes mid-upload frees the slot too")
  void anExpiredDeadlineFreesTheSlot() throws Exception {
    startOneSlotServer();
    byte[] message = EmlFixtures.plainText();
    int headerEnd = EmlFixtures.headerBlockEnd(message);

    Call stalled = Call.start(stub().withDeadlineAfter(200, TimeUnit.MILLISECONDS))
        .chunk(message, 0, headerEnd, false)
        .await();
    assertThat(stalled.code()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);

    Call next = Call.start(stub()).finishUpload(message, 0).await();
    assertThat(next.completed).isTrue();
  }

  @Test
  @DisplayName("cancelling mid-parse stops the parse instead of faulting it")
  void cancellingMidParseStopsTheParse() throws Exception {
    startOneSlotServer();
    byte[] message = EmlFixtures.manyAttachments(200);

    // The channel delivers on the server's thread, so this cancel lands in
    // the middle of the walk, right after the body part went out.
    Call cancelled = Call.start(stub(), Call::cancel).finishUpload(message, 0).await();
    assertThat(cancelled.code()).isEqualTo(Status.Code.CANCELLED);
    assertThat(cancelled.events.stream().filter(ParseEmailResponse::hasAttachment).count())
        .as("nothing was written after the client left")
        .isZero();

    // On a one-slot server the next call runs only after the abandoned parse
    // has given its slot back, so by now that parse is over.
    Call next = Call.start(stub()).finishUpload(EmlFixtures.plainText(), 0).await();
    assertThat(next.completed).isTrue();
    assertThat(service.metrics().render())
        .as("the abandoned parse stopped quietly, neither a fault nor a trailer: writing"
            + " to the cancelled call used to throw and count it as an INTERNAL failure")
        .contains("rejected=0,failed=0}")
        .contains("content{body_parts=1,attachments=0,");
  }

  // --- driven directly: errors on the parse thread ------------------------

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
