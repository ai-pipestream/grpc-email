package ai.pipestream.email;

import ai.pipestream.email.parse.EmlParser;
import ai.pipestream.email.parse.MsgParser;
import ai.pipestream.email.parse.ParseOptions;
import ai.pipestream.email.parse.ParseSink;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * A ParseSink that keeps what a parser emitted, for tests that look at one
 * parser on its own rather than through the gRPC service.
 */
final class RecordingSink implements ParseSink {

  final List<EmailInfo> infos = new ArrayList<>();
  final List<BodyPart> bodies = new ArrayList<>();
  final List<Attachment> attachments = new ArrayList<>();
  final List<String> warnings = new ArrayList<>();
  boolean encrypted;

  /** Attachments listed with their bytes, up to 1 MiB each. */
  static final ParseOptions OPTIONS =
      new ParseOptions("test-doc", "", "", true, true, 1 << 20);

  /** Everything EmlParser emits for {@code bytes}. */
  static RecordingSink eml(byte[] bytes) {
    RecordingSink sink = new RecordingSink();
    EmlParser.parse(bytes, OPTIONS, sink);
    return sink;
  }

  /** Everything MsgParser emits for {@code bytes}. */
  static RecordingSink msg(byte[] bytes) {
    RecordingSink sink = new RecordingSink();
    MsgParser.parse(bytes, OPTIONS, sink);
    return sink;
  }

  @Override
  public void info(EmailInfo info) {
    infos.add(info);
  }

  @Override
  public void bodyPart(BodyPart part) {
    bodies.add(part);
  }

  @Override
  public void attachment(Attachment attachment) {
    attachments.add(attachment);
  }

  @Override
  public void warn(String warning) {
    warnings.add(warning);
  }

  @Override
  public void encrypted() {
    encrypted = true;
  }
}
