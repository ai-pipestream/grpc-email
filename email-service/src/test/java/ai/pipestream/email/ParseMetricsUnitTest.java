package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.server.ParseMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The metrics line is documented in the README and read by log scrapers, so
 * its exact shape is contract, not cosmetics. These tests pin the words.
 */
class ParseMetricsUnitTest {

  @Test
  void freshMetricsRenderAllZeros() {
    assertThat(new ParseMetrics().render()).isEqualTo(
        "grpc-email metrics: messages{parsed=0,rejected=0,failed=0}"
            + " content{body_parts=0,attachments=0,bytes=0}");
  }

  @Test
  void everyCounterLandsInItsOwnSlot() {
    ParseMetrics metrics = new ParseMetrics();
    metrics.messageParsed();
    metrics.messageParsed();
    metrics.messageRejected();
    metrics.messageFailed();
    metrics.contentEmitted(3, 2);
    metrics.contentEmitted(1, 0);
    metrics.bytesReceived(1000);
    metrics.bytesReceived(24);
    assertThat(metrics.render()).isEqualTo(
        "grpc-email metrics: messages{parsed=2,rejected=1,failed=1}"
            + " content{body_parts=4,attachments=2,bytes=1024}");
    assertThat(metrics.render())
        .as("rendering reports state; it must not consume it")
        .isEqualTo(metrics.render());
  }

  @Test
  void concurrentIncrementsAreNeverLost() throws Exception {
    ParseMetrics metrics = new ParseMetrics();
    int threads = 16;
    int perThread = 1000;
    List<Thread> workers = new ArrayList<>();
    for (int index = 0; index < threads; index++) {
      workers.add(Thread.ofVirtual().start(() -> {
        for (int i = 0; i < perThread; i++) {
          metrics.messageParsed();
          metrics.contentEmitted(1, 1);
          metrics.bytesReceived(2);
        }
      }));
    }
    for (Thread worker : workers) {
      worker.join(TimeUnit.SECONDS.toMillis(30));
      assertThat(worker.isAlive()).isFalse();
    }
    assertThat(metrics.render()).isEqualTo(
        "grpc-email metrics: messages{parsed=" + (threads * perThread)
            + ",rejected=0,failed=0} content{body_parts=" + (threads * perThread)
            + ",attachments=" + (threads * perThread)
            + ",bytes=" + (2L * threads * perThread) + "}");
  }
}
