package ai.pipestream.email.parse;

/**
 * The bytes claim to be an email but cannot be read as one: a truncated
 * header block, a MIME tree that ends mid-part, a CFB whose directory is
 * corrupt. Maps to gRPC INVALID_ARGUMENT.
 */
public final class InvalidEmailException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidEmailException(String message) {
    super(message);
  }

  public InvalidEmailException(String message, Throwable cause) {
    super(message, cause);
  }
}
