package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailInfo;

/**
 * Where a parser puts events the instant it has them. Implementations write
 * straight to the wire; a parser must never collect results in a list and
 * hand them over at the end, because the stream is the product.
 *
 * <p>Every attachment a parser finds is offered here even when the client
 * did not ask for attachment events. The sink decides what reaches the wire
 * and keeps the counts either way, so ParseStatus can report attachments the
 * client chose not to see.
 */
public interface ParseSink {

  /** Emits the envelope. Only the first call reaches the wire. */
  void info(EmailInfo info);

  /** Emits one decoded body part. */
  void bodyPart(BodyPart part);

  /** Offers one attachment; the sink applies the client's listing options. */
  void attachment(Attachment attachment);

  /** Records a degradation. Warnings ride the ParseStatus trailer. */
  void warn(String warning);
}
