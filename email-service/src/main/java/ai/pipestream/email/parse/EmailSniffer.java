package ai.pipestream.email.parse;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Decides what a prefix of bytes is, without ever consulting the advisory
 * content type. Two questions matter: is this an OLE2 compound file (the
 * .msg container), and does an RFC 822 header block start here and where
 * does it end.
 *
 * <p>The header-block scan is incremental so the server can answer "what is
 * the subject" from the first few kilobytes of a chunked upload, long before
 * the body has arrived.
 */
public final class EmailSniffer {

  /** OLE2 / CFB signature: every .msg starts with these eight bytes. */
  private static final byte[] OLE2_MAGIC = {
    (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
    (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1,
  };

  /**
   * Header names that make a block credibly RFC 822. A file that merely
   * happens to start with "word:" is not mail; one of these has to be there.
   */
  private static final Set<String> MAIL_HEADERS = Set.of(
      "from", "to", "cc", "bcc", "subject", "date", "message-id", "received",
      "mime-version", "return-path", "content-type", "sender", "reply-to",
      "delivered-to", "in-reply-to", "references", "x-original-to");

  private EmailSniffer() {}

  /** How many bytes are needed before {@link #isOle2} can answer. */
  public static int ole2MagicLength() {
    return OLE2_MAGIC.length;
  }

  /** True when the buffer starts with the OLE2 compound-file signature. */
  public static boolean isOle2(byte[] buffer, int length) {
    if (length < OLE2_MAGIC.length) {
      return false;
    }
    for (int index = 0; index < OLE2_MAGIC.length; index++) {
      if (buffer[index] != OLE2_MAGIC[index]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Finds the empty line that terminates an RFC 822 header block.
   *
   * @param buffer bytes received so far
   * @param length how many of them are valid
   * @param from index to resume scanning from; callers keep this across
   *     chunks so a long upload is scanned once, not once per chunk
   * @return the length of the header block including its terminating line
   *     break, or -1 when the block has not ended yet
   */
  public static int headerBlockLength(byte[] buffer, int length, int from) {
    for (int index = Math.max(0, from); index < length; index++) {
      if (buffer[index] != '\n') {
        continue;
      }
      if (index + 1 < length && buffer[index + 1] == '\n') {
        return index + 1;
      }
      if (index + 2 < length && buffer[index + 1] == '\r' && buffer[index + 2] == '\n') {
        return index + 1;
      }
    }
    return -1;
  }

  /**
   * Where to resume {@link #headerBlockLength} after an inconclusive scan.
   * A terminator can straddle a chunk boundary by up to two bytes.
   */
  public static int rescanFrom(int length) {
    return Math.max(0, length - 2);
  }

  /**
   * True when the given prefix reads as an RFC 822 header block: the first
   * line is a field, and at least one recognizable mail field is present.
   * Continuation lines and unknown X- fields are fine; a block made only of
   * unknown fields is not accepted, because that is how arbitrary text files
   * sneak in.
   */
  public static boolean looksLikeHeaderBlock(byte[] buffer, int length) {
    if (length <= 0) {
      return false;
    }
    String block = new String(buffer, 0, length, StandardCharsets.ISO_8859_1);
    boolean firstLine = true;
    boolean recognized = false;
    for (String line : block.split("\r\n|\n|\r", -1)) {
      if (line.isEmpty()) {
        break;
      }
      if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
        if (firstLine) {
          return false;
        }
        continue;
      }
      String name = fieldName(line);
      if (name == null) {
        return false;
      }
      firstLine = false;
      if (MAIL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        recognized = true;
      }
    }
    return recognized;
  }

  /**
   * The field name of a header line, or null when the line is not a header.
   * RFC 5322 field names are printable ASCII without space or colon.
   */
  private static String fieldName(String line) {
    int colon = line.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    for (int index = 0; index < colon; index++) {
      char character = line.charAt(index);
      if (character < 33 || character > 126) {
        return null;
      }
    }
    return line.substring(0, colon);
  }
}
