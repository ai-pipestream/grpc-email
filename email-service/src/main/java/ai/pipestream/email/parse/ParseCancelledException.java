package ai.pipestream.email.parse;

/**
 * The client went away (it cancelled, or its deadline passed) while its
 * message was being parsed. Thrown from {@link ParseSink#checkpoint()} so a
 * parser stops between parts instead of decoding the rest for nobody; the
 * server catches it and answers no one.
 */
public final class ParseCancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ParseCancelledException() {
    super("the client cancelled the call", null, false, false);
  }
}
