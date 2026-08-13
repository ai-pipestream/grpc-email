package ai.pipestream.email.parse;

/**
 * The bytes are not an email message this collector understands. Maps to
 * gRPC UNIMPLEMENTED: the input was well-formed enough to identify, it is
 * simply not our format (a ZIP, a PDF, or an OLE2 container that holds
 * something other than a MAPI message).
 */
public final class UnsupportedFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnsupportedFormatException(String message) {
    super(message);
  }

  public UnsupportedFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
