package ai.pipestream.email.parse;

import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.angus.mail.util.UUDecoderStream;

/**
 * Content-Transfer-Encoding decoding for a part that Jakarta Mail's strict
 * decoders refused.
 *
 * <p>Jakarta Mail can tolerate a damaged base64 or uuencoded body, or an
 * encoding it has never heard of, but only through JVM-wide System
 * properties ({@code mail.mime.base64.ignoreerrors},
 * {@code mail.mime.uudecode.ignoreerrors},
 * {@code mail.mime.ignoreunknownencoding}), the last of them frozen the
 * first time {@code MimeUtility} loads. Setting them on a {@code Session}
 * changes nothing. Setting them globally would also hide the damage, because
 * a tolerant decoder drops a bad tail without a word. So the strict decoders
 * run first, and only a part they refuse comes here, where the same
 * tolerance is applied to that one part and the caller learns what was
 * repaired. Nothing is invented: a base64 group cut short yields only the
 * bytes its characters fully determine.
 */
final class TransferDecoding {

  /**
   * What could be recovered from one part.
   *
   * @param bytes the decoded payload, as much of it as survived
   * @param damage what was wrong with the encoding; absent when the
   *     encoding itself was sound (the strict failure lay elsewhere, for
   *     example in the charset)
   */
  record Recovered(byte[] bytes, Optional<String> damage) {

    /** Bytes whose encoding was sound. */
    static Recovered sound(byte[] bytes) {
      return new Recovered(bytes, Optional.empty());
    }

    /** Bytes that survived a damaged encoding, and what the damage was. */
    static Recovered damaged(byte[] bytes, String damage) {
      return new Recovered(bytes, Optional.of(damage));
    }
  }

  /** Base64 alphabet value per byte; {@link #NOT_BASE64} or {@link #PAD} otherwise. */
  private static final byte[] BASE64_VALUES = base64Values();
  private static final byte NOT_BASE64 = -1;
  private static final byte PAD = -2;

  private TransferDecoding() {}

  private static byte[] base64Values() {
    byte[] values = new byte[256];
    Arrays.fill(values, NOT_BASE64);
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (int index = 0; index < alphabet.length(); index++) {
      values[alphabet.charAt(index)] = (byte) index;
    }
    values['='] = PAD;
    return values;
  }

  /**
   * Decodes the part's raw content with tolerant decoders.
   *
   * @throws MessagingException when the part has no raw content to read
   * @throws IOException when the raw content cannot be read at all
   */
  static Recovered recover(Part part) throws MessagingException, IOException {
    String encoding = encoding(part);
    byte[] raw;
    try (InputStream stream = rawStream(part)) {
      raw = stream.readAllBytes();
    }
    return switch (encoding) {
      case "", "7bit", "8bit", "binary" -> Recovered.sound(raw);
      case "base64" -> base64(raw);
      case "quoted-printable" -> quotedPrintable(raw);
      case "uuencode", "x-uuencode", "x-uue" -> uudecode(raw);
      default -> Recovered.damaged(raw, "unknown Content-Transfer-Encoding '" + encoding
          + "'; the bytes were kept undecoded");
    };
  }

  /**
   * Base64 decoding for a stream the strict decoder refused: line breaks and
   * stray bytes are skipped (as the strict decoder skips them too), every
   * complete group of four characters decodes, a group cut short by the end
   * of the data or by padding yields the one or two bytes its characters
   * determine, and data that resumes after padding (two encoded runs pasted
   * together) decodes as the second run it is.
   */
  static Recovered base64(byte[] encoded) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length / 4 * 3 + 3);
    int bits = 0;
    int held = 0;
    boolean inPadding = false;
    boolean strayPadding = false;
    boolean resumed = false;
    boolean dangling = false;
    for (byte b : encoded) {
      int value = BASE64_VALUES[b & 0xFF];
      if (value == PAD) {
        if (held == 0 && !inPadding) {
          strayPadding = true;
        }
        dangling |= held == 1;
        flushPartial(out, bits, held);
        bits = 0;
        held = 0;
        inPadding = true;
        continue;
      }
      if (value == NOT_BASE64) {
        continue;
      }
      if (inPadding) {
        resumed = true;
        inPadding = false;
      }
      bits = (bits << 6) | value;
      if (++held == 4) {
        out.write(bits >> 16);
        out.write(bits >> 8);
        out.write(bits);
        bits = 0;
        held = 0;
      }
    }
    boolean truncated = held > 1;
    dangling |= held == 1;
    flushPartial(out, bits, held);

    List<String> damage = new ArrayList<>();
    if (truncated) {
      damage.add("the data ends partway through a group of four characters");
    }
    if (dangling) {
      damage.add("a lone character that cannot form a byte was dropped");
    }
    if (strayPadding) {
      damage.add("a padding character stands where data was expected");
    }
    if (resumed) {
      damage.add("data resumes after padding");
    }
    return damage.isEmpty()
        ? Recovered.sound(out.toByteArray())
        : Recovered.damaged(out.toByteArray(),
            "damaged base64 (" + String.join("; ", damage) + ")");
  }

  /** The bytes a group of two or three base64 characters fully determines. */
  private static void flushPartial(ByteArrayOutputStream out, int bits, int held) {
    if (held == 2) {
      out.write(bits >> 4);
    } else if (held == 3) {
      out.write(bits >> 10);
      out.write(bits >> 2);
    }
  }

  /** Quoted-printable never fails strictly: Jakarta Mail's decoder keeps a bad escape as is. */
  private static Recovered quotedPrintable(byte[] raw) throws MessagingException, IOException {
    try (InputStream decoded =
             MimeUtility.decode(new ByteArrayInputStream(raw), "quoted-printable")) {
      return Recovered.sound(decoded.readAllBytes());
    }
  }

  /**
   * uudecode strictly first, so a sound body is reported as sound, then with
   * Jakarta Mail's own tolerance (the per-stream form of
   * {@code mail.mime.uudecode.ignoreerrors} and
   * {@code mail.mime.uudecode.ignoremissingbeginend}).
   */
  private static Recovered uudecode(byte[] raw) throws IOException {
    try (InputStream strict = new UUDecoderStream(new ByteArrayInputStream(raw), false, false)) {
      return Recovered.sound(strict.readAllBytes());
    } catch (IOException malformed) {
      try (InputStream tolerant =
               new UUDecoderStream(new ByteArrayInputStream(raw), true, true)) {
        return Recovered.damaged(tolerant.readAllBytes(),
            "damaged uuencoding (" + malformed.getMessage() + ")");
      }
    }
  }

  /** The declared transfer encoding, lowercased, or empty when none was declared. */
  private static String encoding(Part part) throws MessagingException {
    String declared = part instanceof MimeBodyPart body ? body.getEncoding()
        : part instanceof MimeMessage message ? message.getEncoding()
        : null;
    return declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
  }

  /** The part's content exactly as transmitted, before any transfer decoding. */
  private static InputStream rawStream(Part part) throws MessagingException {
    if (part instanceof MimeBodyPart body) {
      return body.getRawInputStream();
    }
    if (part instanceof MimeMessage message) {
      return message.getRawInputStream();
    }
    throw new MessagingException("part has no raw content stream: " + part.getClass().getName());
  }
}
