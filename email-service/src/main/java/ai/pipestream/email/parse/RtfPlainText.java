package ai.pipestream.email.parse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pulls readable text out of an RTF body. This is deliberately not a layout
 * engine: RTF-only messages are rare, and design.md scopes v1 to "plain text
 * extracted from the RTF, with a warning".
 *
 * <p>What it handles: group nesting, control words and their numeric
 * parameters, the \\'hh byte escape, \\uN Unicode escapes with \\ucN skip
 * counts, and the destination groups (font tables, colour tables, embedded
 * objects, generator stamps) whose contents are markup rather than text.
 *
 * <p><b>Code pages.</b> A \\'hh escape is a byte in a code page, never a
 * character of its own. The code page is the document's (\\ansicpgN, or what
 * \\mac, \\pc and \\pca imply; 1252 when the header says nothing) unless the
 * font in force names another: a font table entry's \\fcharsetN or \\cpgN
 * applies to every run set in that font (\\fN, scoped to its group; \\deffN
 * names the font text starts in). Consecutive bytes are decoded together, so
 * a double-byte code page reads two escapes as one character, and a trail
 * byte written as a bare ASCII character still completes its lead byte. A
 * code page this JVM cannot decode keeps its bytes as ISO-8859-1 and is
 * reported in {@link Extracted#undecodableCodePages()}.
 */
public final class RtfPlainText {

  /**
   * Destinations whose contents are never body text. Anything introduced by
   * \\* is skipped too, which covers the long tail.
   */
  private static final Set<String> SKIPPED_DESTINATIONS = Set.of(
      "fonttbl", "colortbl", "stylesheet", "listtable", "listoverridetable", "rsidtbl",
      "generator", "info", "pict", "object", "objdata", "themedata", "datastore",
      "colorschememapping", "latentstyles", "filetbl", "xmlnstbl", "mmathPr", "upr",
      "header", "footer", "footnote", "revtbl", "protusertbl");

  /** Ten digits cover every 32-bit value; more is malformed input. */
  private static final int MAX_PARAMETER_DIGITS = 10;

  /** The code page of an RTF document whose header names none. */
  public static final int DEFAULT_CODE_PAGE = 1252;

  /**
   * \\fcharsetN as a code page. 0 is ANSI, which is 1252; 1 (default) and 2
   * (symbol) are not listed and follow the document's code page.
   */
  private static final Map<Integer, Integer> FONT_CHARSET_CODE_PAGES = Map.ofEntries(
      Map.entry(0, 1252), Map.entry(77, 10000), Map.entry(128, 932), Map.entry(129, 949),
      Map.entry(130, 1361), Map.entry(134, 936), Map.entry(136, 950), Map.entry(161, 1253),
      Map.entry(162, 1254), Map.entry(163, 1258), Map.entry(177, 1255), Map.entry(178, 1256),
      Map.entry(186, 1257), Map.entry(204, 1251), Map.entry(222, 874), Map.entry(238, 1250),
      Map.entry(254, 437), Map.entry(255, 850));

  private RtfPlainText() {}

  /**
   * The text of an RTF body and how its \\'hh bytes were read.
   *
   * @param text the best-effort plain text
   * @param codePage the document's code page: \\ansicpgN, or what the header
   *     implies, or {@link #DEFAULT_CODE_PAGE}
   * @param charset the lowercased charset that code page was decoded as;
   *     "iso-8859-1" when this JVM has no charset for it
   * @param undecodableCodePages every code page (the document's or a font's)
   *     that bytes arrived in and this JVM could not decode; those bytes were
   *     kept as ISO-8859-1. Empty when every byte was decoded in its own
   *     code page
   */
  public record Extracted(
      String text, int codePage, String charset, Set<Integer> undecodableCodePages) {}

  /** Extracts best-effort plain text; returns an empty string for empty input. */
  public static String extract(String rtf) {
    return read(rtf).text();
  }

  /** What a group restores when it closes. */
  private record Group(boolean skipping, int unicodeSkip, Integer font, boolean fontTable) {}

  /** Consecutive \\'hh bytes in one code page, decoded together when the run ends. */
  private static final class ByteRun {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final Set<Integer> undecodable = new TreeSet<>();
    private int codePage;
    /** The last byte opens a two-byte character whose second byte has not come. */
    private boolean leadPending;

    private void add(int value, int inCodePage, StringBuilder text) {
      if (bytes.size() > 0 && codePage != inCodePage) {
        flush(text);
      }
      codePage = inCodePage;
      bytes.write(value);
      leadPending = !leadPending && CodePages.isLeadByte(inCodePage, value);
    }

    /** Takes a bare character as the trail byte a pending lead byte is waiting for. */
    private boolean completes(char character) {
      // A trail byte below 0x80 lies in 0x40..0x7E in every double-byte
      // Windows code page; a space or a digit after a lead byte is text.
      if (!leadPending || character < 0x40 || character > 0x7E) {
        return false;
      }
      bytes.write(character);
      leadPending = false;
      return true;
    }

    private void flush(StringBuilder text) {
      if (bytes.size() == 0) {
        return;
      }
      Charset charset = CodePages.charset(codePage);
      if (charset == null) {
        undecodable.add(codePage);
        charset = StandardCharsets.ISO_8859_1;
      }
      text.append(new String(bytes.toByteArray(), charset));
      bytes.reset();
      leadPending = false;
    }
  }

  /** Extracts best-effort plain text and reports the code pages it was read in. */
  public static Extracted read(String rtf) {
    if (rtf == null || rtf.isEmpty()) {
      return new Extracted("", DEFAULT_CODE_PAGE, charsetName(DEFAULT_CODE_PAGE), Set.of());
    }
    StringBuilder text = new StringBuilder();
    ByteRun run = new ByteRun();
    Deque<Group> groups = new ArrayDeque<>();
    Map<Integer, Integer> fontCodePages = new HashMap<>();
    int documentCodePage = DEFAULT_CODE_PAGE;
    boolean explicitCodePage = false;
    Integer defaultFont = null;
    Integer font = null;
    boolean fontTable = false;
    int tableFont = -1;
    boolean skipping = false;
    int unicodeSkip = 1;
    int pendingSkip = 0;
    int index = 0;
    int length = rtf.length();

    while (index < length) {
      char character = rtf.charAt(index);
      switch (character) {
        case '{' -> {
          run.flush(text);
          groups.push(new Group(skipping, unicodeSkip, font, fontTable));
          index++;
        }
        case '}' -> {
          run.flush(text);
          Group group = groups.isEmpty() ? new Group(false, 1, null, false) : groups.pop();
          skipping = group.skipping();
          unicodeSkip = group.unicodeSkip();
          font = group.font();
          fontTable = group.fontTable();
          pendingSkip = 0;
          index++;
        }
        case '\\' -> {
          int next = index + 1;
          if (next >= length) {
            index = length;
            break;
          }
          char control = rtf.charAt(next);
          if (control == '\\' || control == '{' || control == '}') {
            if (!skipping && pendingSkip == 0 && !run.completes(control)) {
              run.flush(text);
              text.append(control);
            }
            pendingSkip = Math.max(0, pendingSkip - 1);
            index = next + 1;
          } else if (control == '\'') {
            if (next + 2 < length) {
              int value = hex(rtf.charAt(next + 1), rtf.charAt(next + 2));
              if (value >= 0 && !skipping && pendingSkip == 0) {
                // A byte in the code page of the font in force, or of the
                // document when that font names none.
                Integer inFont = font != null ? font : defaultFont;
                run.add(value,
                    inFont == null
                        ? documentCodePage
                        : fontCodePages.getOrDefault(inFont, documentCodePage),
                    text);
              }
              pendingSkip = Math.max(0, pendingSkip - 1);
              index = next + 3;
            } else {
              index = length;
            }
          } else if (control == '*') {
            run.flush(text);
            skipping = true;
            index = next + 1;
          } else if (Character.isLetter(control)) {
            run.flush(text);
            int wordEnd = next;
            while (wordEnd < length && Character.isLetter(rtf.charAt(wordEnd))) {
              wordEnd++;
            }
            String word = rtf.substring(next, wordEnd);
            int paramEnd = wordEnd;
            boolean negative = paramEnd < length && rtf.charAt(paramEnd) == '-';
            if (negative) {
              paramEnd++;
            }
            int digitsStart = paramEnd;
            while (paramEnd < length && isAsciiDigit(rtf.charAt(paramEnd))) {
              paramEnd++;
            }
            Integer parameter = paramEnd > digitsStart
                ? Integer.valueOf(parameter(rtf, digitsStart, paramEnd, negative))
                : null;
            // A single space after a control word is its delimiter, not text.
            int after = paramEnd < length && rtf.charAt(paramEnd) == ' ' ? paramEnd + 1 : paramEnd;

            if (fontTable) {
              // The one skipped destination that is read: which code page
              // each font's bytes are in.
              if (word.equals("f") && parameter != null) {
                tableFont = parameter;
              } else if (word.equals("cpg") && parameter != null && parameter > 0) {
                fontCodePages.put(tableFont, parameter);
              } else if (word.equals("fcharset") && parameter != null) {
                Integer codePage = FONT_CHARSET_CODE_PAGES.get(parameter);
                if (codePage != null) {
                  fontCodePages.putIfAbsent(tableFont, codePage);
                }
              }
            } else if (word.equals("fonttbl")) {
              skipping = true;
              fontTable = true;
            } else if (SKIPPED_DESTINATIONS.contains(word)) {
              skipping = true;
            } else if (word.equals("uc")) {
              unicodeSkip = parameter == null ? 1 : Math.max(0, parameter);
            } else if (word.equals("u") && parameter != null) {
              // A signed 16-bit code unit; anything outside that range is
              // malformed and decodes to nothing rather than to garbage.
              if (!skipping && parameter >= Short.MIN_VALUE && parameter <= 0xFFFF) {
                text.append((char) (parameter < 0 ? parameter + 65536 : parameter));
              }
              pendingSkip = unicodeSkip;
              index = after;
              continue;
            } else if (!skipping && pendingSkip == 0) {
              switch (word) {
                case "par", "line", "sect" -> text.append('\n');
                case "tab" -> text.append('\t');
                case "emdash" -> text.append('—');
                case "endash" -> text.append('–');
                case "lquote" -> text.append('‘');
                case "rquote" -> text.append('’');
                case "ldblquote" -> text.append('“');
                case "rdblquote" -> text.append('”');
                case "bullet" -> text.append('•');
                case "nbsp" -> text.append(' ');
                case "f" -> font = parameter == null ? font : parameter;
                case "plain" -> font = defaultFont;
                case "deff" -> defaultFont = parameter == null ? defaultFont : parameter;
                case "ansicpg" -> {
                  if (parameter != null && parameter > 0) {
                    documentCodePage = parameter;
                    explicitCodePage = true;
                  }
                }
                // The header's character set, unless \ansicpg names the page.
                case "mac" -> documentCodePage = explicitCodePage ? documentCodePage : 10000;
                case "pc" -> documentCodePage = explicitCodePage ? documentCodePage : 437;
                case "pca" -> documentCodePage = explicitCodePage ? documentCodePage : 850;
                default -> { }
              }
            }
            pendingSkip = Math.max(0, pendingSkip - 1);
            index = after;
          } else {
            index = next + 1;
          }
        }
        case '\r', '\n' -> index++;
        default -> {
          if (!skipping && pendingSkip == 0 && !run.completes(character)) {
            run.flush(text);
            text.append(character);
          }
          pendingSkip = Math.max(0, pendingSkip - 1);
          index++;
        }
      }
    }
    run.flush(text);
    return new Extracted(text.toString().strip(), documentCodePage,
        charsetName(documentCodePage), Set.copyOf(run.undecodable));
  }

  /**
   * Puts how an RTF body's bytes were read on its BodyPart, and warns about
   * every code page that could not be decoded. {@code charset} is what the
   * RTF declared (the charset of its code page, or "cpN" when this JVM has
   * none), {@code charset_detected} what its bytes were decoded as, and
   * {@code charset_fallback} whether any bytes had to be kept as ISO-8859-1.
   *
   * @param where what the warning calls this body, for example "RTF body"
   */
  static void describe(
      Extracted extracted, ai.pipestream.email.v1.BodyPart.Builder body, String where,
      ParseSink sink) {
    boolean known = CodePages.charset(extracted.codePage()) != null;
    body.setCharset(known ? extracted.charset() : "cp" + extracted.codePage())
        .setCharsetDetected(extracted.charset())
        .setCharsetFallback(!extracted.undecodableCodePages().isEmpty());
    for (int codePage : new TreeSet<>(extracted.undecodableCodePages())) {
      sink.warn(where + " carries bytes in code page " + codePage
          + ", which this service cannot decode; they were read as iso-8859-1");
    }
  }

  /** The charset a code page is decoded as: its own, or ISO-8859-1 when unknown. */
  private static String charsetName(int codePage) {
    Charset charset = CodePages.charset(codePage);
    return CodePages.name(charset == null ? StandardCharsets.ISO_8859_1 : charset);
  }

  /**
   * A control word's numeric parameter. RTF defines these as signed 16-bit
   * integers (a few as 32-bit), so at most ten digits are read, as a long,
   * and the result is clamped to the int range: a digit run too long for
   * any integer is malformed input, not a reason to fail the message.
   */
  private static int parameter(String rtf, int from, int to, boolean negative) {
    long value = 0;
    for (int index = from; index < to && index < from + MAX_PARAMETER_DIGITS; index++) {
      value = value * 10 + (rtf.charAt(index) - '0');
    }
    if (to - from > MAX_PARAMETER_DIGITS) {
      value = Long.MAX_VALUE;
    }
    long signed = negative ? -value : value;
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, signed));
  }

  /** RTF is 7-bit: only ASCII digits form a parameter, never other scripts' digits. */
  private static boolean isAsciiDigit(char character) {
    return character >= '0' && character <= '9';
  }

  private static int hex(char high, char low) {
    int highDigit = Character.digit(high, 16);
    int lowDigit = Character.digit(low, 16);
    return highDigit < 0 || lowDigit < 0 ? -1 : highDigit * 16 + lowDigit;
  }
}
