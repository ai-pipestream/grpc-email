package ai.pipestream.email.parse;

import jakarta.mail.internet.MimeUtility;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns a text part's transfer-decoded bytes into text, strictly. Jakarta
 * Mail decodes a body through a replacing reader, so bytes that are not valid
 * in the declared charset never fail there: an 8-bit UTF-8 body with no
 * charset parameter came out as U+FFFD under the US-ASCII default, and
 * nothing said so. Here every decode reports, and a mismatch is resolved by
 * one fixed rule and always told to the caller.
 *
 * <p><b>The rule.</b>
 * <ol>
 *   <li>The bytes are valid in the declared charset: that is the text.
 *   <li>No charset was declared and the bytes are US-ASCII: that is the text
 *       (RFC 2045's default), and nothing is reported.
 *   <li>Otherwise the declared charset is wrong for the bytes, unknown, or
 *       missing. When the bytes are valid UTF-8 they are UTF-8.
 *   <li>When they are mostly valid in the declared charset (or, with none
 *       usable, in UTF-8), with more intact non-ASCII characters than damaged
 *       sequences, that charset is kept and each damaged sequence becomes one
 *       U+FFFD: a few bad bytes must not turn the rest of a Chinese or UTF-8
 *       body into another charset's mojibake.
 *   <li>Otherwise windows-1252 when every byte is defined there, else
 *       ISO-8859-1, which maps every byte, so nothing is lost.
 * </ol>
 *
 * <p>Steps 3 to 5 warn through the sink, naming the part, and set the typed
 * fields a caller reads: the charset used, whether it was a fallback, and
 * how many sequences were replaced.
 */
final class BodyText {

  private static final Charset WINDOWS_1252 = windows1252();

  private BodyText() {}

  /**
   * A decoded body.
   *
   * @param text the text
   * @param charsetDetected the charset the bytes were decoded with, lowercased
   * @param fallback true when that is not the charset the part declared
   * @param undecodableSequences byte sequences replaced by U+FFFD
   */
  record Decoded(String text, String charsetDetected, boolean fallback, int undecodableSequences) {}

  /**
   * The name a Windows code page is declared under: the charset's own name
   * when this JVM has one, "cp" and the number when it does not (which
   * {@link #decode} then reports as a charset it cannot decode), and empty
   * for no code page at all.
   */
  static String codePageName(int codePage) {
    if (codePage <= 0) {
      return "";
    }
    Charset charset = CodePages.charset(codePage);
    return charset == null ? "cp" + codePage : CodePages.name(charset);
  }

  /**
   * Decodes {@code bytes}, the transfer-decoded content of the part at
   * {@code path}, whose Content-Type declared {@code declaredCharset} (empty
   * for none). A MAPI body stored as bytes goes through the same rule, with
   * the name of its stored code page as the declared charset.
   */
  static Decoded decode(byte[] bytes, String declaredCharset, String path, ParseSink sink) {
    Charset declared = charset(declaredCharset);
    if (declared != null) {
      String strict = strict(bytes, declared);
      if (strict != null) {
        return new Decoded(strict, name(declared), false, 0);
      }
    } else if (declaredCharset.isEmpty() && isAscii(bytes)) {
      return new Decoded(new String(bytes, StandardCharsets.US_ASCII), "us-ascii", false, 0);
    }
    String problem = declaredCharset.isEmpty()
        ? "declared no charset but carries bytes that are not US-ASCII"
        : declared == null
            ? "declared charset '" + declaredCharset + "' which this service cannot decode"
            : "declared charset '" + declaredCharset + "' but its bytes are not valid "
                + name(declared);

    if (!StandardCharsets.UTF_8.equals(declared)) {
      String utf8 = strict(bytes, StandardCharsets.UTF_8);
      if (utf8 != null) {
        sink.warn("part " + path + " " + problem + "; they are valid UTF-8 and were read as utf-8");
        return new Decoded(utf8, "utf-8", true, 0);
      }
    }
    // Mostly valid in the charset it should be in: keep it and count the damage.
    Charset mostly = declared != null && !StandardCharsets.US_ASCII.equals(declared)
        ? declared : StandardCharsets.UTF_8;
    Replaced replaced = replacing(bytes, mostly);
    if (replaced.intactNonAscii() > replaced.damaged()) {
      boolean fallback = !mostly.equals(declared);
      sink.warn("part " + path + " " + (fallback ? problem + "; read as " + name(mostly) + ", and "
              : "has ")
          + replaced.damaged() + " byte sequence" + (replaced.damaged() == 1 ? "" : "s")
          + " that " + (replaced.damaged() == 1 ? "is" : "are") + " not valid " + name(mostly)
          + (replaced.damaged() == 1 ? "; it was" : "; each was") + " replaced with U+FFFD");
      return new Decoded(replaced.text(), name(mostly), fallback, replaced.damaged());
    }
    String windows1252 = WINDOWS_1252 == null || WINDOWS_1252.equals(declared)
        ? null : strict(bytes, WINDOWS_1252);
    if (windows1252 != null) {
      sink.warn("part " + path + " " + problem + "; read as windows-1252");
      return new Decoded(windows1252, "windows-1252", true, 0);
    }
    sink.warn("part " + path + " " + problem
        + "; read as iso-8859-1, which keeps every byte as one character");
    return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), "iso-8859-1",
        !StandardCharsets.ISO_8859_1.equals(declared), 0);
  }

  /**
   * The charset a part declared, or null when it declared none or this JVM
   * cannot decode it.
   */
  private static Charset charset(String declared) {
    if (declared.isEmpty()) {
      return null;
    }
    try {
      // Jakarta Mail's table reads "us-ascii" as ISO-8859-1 so 8-bit bytes
      // survive its replacing reader. Here an 8-bit byte under us-ascii is a
      // mismatch to resolve and report, so the name keeps its own meaning.
      if (StandardCharsets.US_ASCII.equals(Charset.forName(declared))) {
        return StandardCharsets.US_ASCII;
      }
    } catch (IllegalArgumentException notAJavaName) {
      // Fall through: the MIME name may still map to a Java one.
    }
    try {
      // The same table Jakarta Mail uses, which knows what mailers mean:
      // "gb2312" mail is decoded as its superset GB18030, for one.
      return Charset.forName(MimeUtility.javaCharset(declared));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  private static String name(Charset charset) {
    return charset.name().toLowerCase(Locale.ROOT);
  }

  private static boolean isAscii(byte[] bytes) {
    for (byte value : bytes) {
      if (value < 0) {
        return false;
      }
    }
    return true;
  }

  /** {@code bytes} as text when every one of them is valid in {@code charset}, else null. */
  private static String strict(byte[] bytes, Charset charset) {
    try {
      return charset.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      return null;
    }
  }

  /**
   * A decode that replaced what it could not read.
   *
   * @param text the text, one U+FFFD per damaged sequence
   * @param damaged how many sequences were replaced
   * @param intactNonAscii how many non-ASCII characters decoded cleanly
   */
  private record Replaced(String text, int damaged, int intactNonAscii) {}

  /** Decodes {@code bytes} as {@code charset}, counting each sequence it had to replace. */
  private static Replaced replacing(byte[] bytes, Charset charset) {
    CharsetDecoder decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(bytes);
    CharBuffer out = CharBuffer.allocate(
        (int) Math.min(Integer.MAX_VALUE - 8, (long) bytes.length * 2 + 16));
    StringBuilder text = new StringBuilder(bytes.length);
    int damaged = 0;
    int intactNonAscii = 0;
    boolean flushing = false;
    while (true) {
      CoderResult result = flushing ? decoder.flush(out) : decoder.decode(in, out, true);
      out.flip();
      for (int index = 0; index < out.limit(); index++) {
        char character = out.get(index);
        if (character > 0x7F && !Character.isLowSurrogate(character)) {
          intactNonAscii++;
        }
      }
      text.append(out);
      out.clear();
      if (result.isError()) {
        damaged++;
        text.append('�');
        in.position(Math.min(in.limit(), in.position() + Math.max(1, result.length())));
      } else if (result.isUnderflow()) {
        if (flushing) {
          break;
        }
        flushing = true;
      }
      // Overflow: the buffer was drained above; decode on.
    }
    return new Replaced(text.toString(), damaged, intactNonAscii);
  }

  private static Charset windows1252() {
    try {
      return Charset.forName("windows-1252");
    } catch (IllegalArgumentException absent) {
      return null;
    }
  }
}
