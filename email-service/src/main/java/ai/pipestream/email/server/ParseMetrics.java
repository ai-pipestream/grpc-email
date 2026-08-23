package ai.pipestream.email.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lifetime counters for one server process, and the single stdout line that
 * reports them. The counters are the service's; the reporting cadence is the
 * launcher's; keeping the words here means both agree on what the line says.
 *
 * <p>The line format is documented in the README and consumed by log
 * scrapers, so {@link #render()} is part of the observable surface: change it
 * and the test that pins the format fails.
 */
public final class ParseMetrics {

  private final AtomicLong parsed = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong bodyParts = new AtomicLong();
  private final AtomicLong attachments = new AtomicLong();
  private final AtomicLong bytesRead = new AtomicLong();

  /** Counts one message parsed to a successful trailer. */
  public void messageParsed() {
    parsed.incrementAndGet();
  }

  /** Counts one message refused: bad stream shape, over cap, not email. */
  public void messageRejected() {
    rejected.incrementAndGet();
  }

  /** Counts one unexpected parser fault (the INTERNAL path). */
  public void messageFailed() {
    failed.incrementAndGet();
  }

  /** Adds one finished parse's body-part and attachment counts. */
  public void contentEmitted(long bodyPartCount, long attachmentCount) {
    bodyParts.addAndGet(bodyPartCount);
    attachments.addAndGet(attachmentCount);
  }

  /** Adds the size of one fully received message. */
  public void bytesReceived(long count) {
    bytesRead.addAndGet(count);
  }

  /** The metrics line, exactly as the README documents it. */
  public String render() {
    return "grpc-email metrics: messages{parsed=" + parsed.get()
        + ",rejected=" + rejected.get() + ",failed=" + failed.get()
        + "} content{body_parts=" + bodyParts.get()
        + ",attachments=" + attachments.get()
        + ",bytes=" + bytesRead.get() + "}";
  }
}
