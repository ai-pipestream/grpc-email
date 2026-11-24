package ai.pipestream.email.parse;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Windows code page numbers as Java charsets. MAPI properties, TNEF records
 * and RTF bodies all name their text encoding by number.
 */
final class CodePages {

  /** Code pages whose Java name is not a spelling of the number. */
  private static final Map<Integer, String> NAMED = Map.ofEntries(
      Map.entry(437, "IBM437"),
      Map.entry(850, "IBM850"),
      Map.entry(932, "windows-31j"),
      Map.entry(936, "GBK"),
      Map.entry(949, "x-windows-949"),
      Map.entry(950, "x-windows-950"),
      Map.entry(1361, "x-Johab"),
      Map.entry(10000, "x-MacRoman"),
      Map.entry(20866, "KOI8-R"),
      Map.entry(21866, "KOI8-U"),
      Map.entry(28592, "ISO-8859-2"),
      Map.entry(28595, "ISO-8859-5"),
      Map.entry(28597, "ISO-8859-7"),
      Map.entry(28599, "ISO-8859-9"),
      Map.entry(28605, "ISO-8859-15"),
      Map.entry(50220, "ISO-2022-JP"),
      Map.entry(51932, "EUC-JP"),
      Map.entry(51949, "EUC-KR"),
      Map.entry(54936, "GB18030"));

  private CodePages() {}

  /** Code page {@code number} as a charset, or null when this JVM has none for it. */
  static Charset charset(int number) {
    if (number <= 0) {
      return null;
    }
    switch (number) {
      case 65001:
        return StandardCharsets.UTF_8;
      case 1200:
        return StandardCharsets.UTF_16LE;
      case 1201:
        return StandardCharsets.UTF_16BE;
      case 20127:
        return StandardCharsets.US_ASCII;
      case 28591:
        return StandardCharsets.ISO_8859_1;
      default:
        break;
    }
    String named = NAMED.get(number);
    for (String name : named != null
        ? new String[] {named}
        : new String[] {"windows-" + number, "cp" + number, "x-windows-" + number}) {
      try {
        return Charset.forName(name);
      } catch (IllegalArgumentException unknown) {
        // try the next spelling
      }
    }
    return null;
  }

  /** The lowercased name of a charset, as BodyPart reports one. */
  static String name(Charset charset) {
    return charset.name().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether {@code value} opens a two-byte character in code page
   * {@code number}. False for every single-byte code page.
   */
  static boolean isLeadByte(int number, int value) {
    return switch (number) {
      case 932 -> (value >= 0x81 && value <= 0x9F) || (value >= 0xE0 && value <= 0xFC);
      case 936, 949, 950 -> value >= 0x81 && value <= 0xFE;
      case 1361 -> (value >= 0x84 && value <= 0xD3) || (value >= 0xD8 && value <= 0xDE)
          || (value >= 0xE0 && value <= 0xF9);
      default -> false;
    };
  }
}
