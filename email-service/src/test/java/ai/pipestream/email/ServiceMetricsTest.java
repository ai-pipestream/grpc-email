package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.server.EmailParseServiceImpl;
import ai.pipestream.email.v1.EmailChunk;
import ai.pipestream.email.v1.ParseEmailOptions;
import ai.pipestream.email.v1.ParseEmailRequest;
import ai.pipestream.email.v1.ParseEmailResponse;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The service's lifetime counters, observed through real parses against a
 * dedicated service instance so the totals are deterministic. This is the
 * seam the launcher's metrics thread reads; the line it prints is pinned
 * byte for byte.
 */
class ServiceMetricsTest {

  private static ExecutorService executor;

  @BeforeAll
  static void start() {
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterAll
  static void stop() {
    executor.shutdown();
  }

  /** Drives one call directly against the service, no transport in between. */
  private static void drive(EmailParseServiceImpl service, byte[] bytes)
      throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    StreamObserver<ParseEmailRequest> requests =
        service.parseEmail(new StreamObserver<>() {
          @Override
          public void onNext(ParseEmailResponse event) {}

          @Override
          public void onError(Throwable error) {
            done.countDown();
          }

          @Override
          public void onCompleted() {
            done.countDown();
          }
        });
    requests.onNext(ParseEmailRequest.newBuilder()
        .setOptions(ParseEmailOptions.newBuilder().setDocumentId("metrics"))
        .build());
    requests.onNext(ParseEmailRequest.newBuilder()
        .setChunk(EmailChunk.newBuilder()
            .setData(ByteString.copyFrom(bytes))
            .setComplete(true))
        .build());
    requests.onCompleted();
    assertThat(done.await(30, TimeUnit.SECONDS)).as("call finished").isTrue();
  }

  @Test
  void countersAccumulateAcrossOutcomesAndRenderTheDocumentedLine() throws Exception {
    EmailParseServiceImpl service =
        new EmailParseServiceImpl(4L * 1024 * 1024, 64 * 1024, 4, executor);
    byte[] eml = EmlFixtures.plainText();

    drive(service, eml);

    byte[] garbage = new byte[256];
    for (int index = 0; index < garbage.length; index++) {
      garbage[index] = (byte) (index * 31 + 7);
    }
    drive(service, garbage);

    assertThat(service.metrics().render()).isEqualTo(
        "grpc-email metrics: messages{parsed=1,rejected=1,failed=0}"
            + " content{body_parts=1,attachments=0,bytes=" + (eml.length + garbage.length) + "}");
  }
}
