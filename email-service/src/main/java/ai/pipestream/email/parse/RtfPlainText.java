package ai.pipestream.email.parse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Pulls readable text out of an RTF body. This is deliberately not a layout
 * engine: RTF-only messages are rare, and design.md scopes v1 to "plain text
 * extracted from the RTF, with a warning".
 *
 * <p>What it handles: group nesting, control words and their numeric
 * parameters, the \\'hh byte escape, \\uN Unicode escapes with \\ucN skip
 * counts, and the destination groups (font tables, colour tables, embedded
 * objects, generator stamps) whose contents are markup rather than text.
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

  private RtfPlainText() {}

  /** Extracts best-effort plain text; returns an empty string for empty input. */
  public static String extract(String rtf) {
    if (rtf == null || rtf.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    Deque<Boolean> groups = new ArrayDeque<>();
    Deque<Integer> skips = new ArrayDeque<>();
    boolean skipping = false;
    int unicodeSkip = 1;
    int pendingSkip = 0;
    int index = 0;
    int length = rtf.length();

    while (index < length) {
      char character = rtf.charAt(index);
      switch (character) {
        case '{' -> {
          groups.push(skipping);
          skips.push(unicodeSkip);
          index++;
        }
        case '}' -> {
          skipping = groups.isEmpty() ? false : groups.pop();
          unicodeSkip = skips.isEmpty() ? 1 : skips.pop();
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
            if (!skipping && pendingSkip == 0) {
              text.append(control);
            }
            pendingSkip = Math.max(0, pendingSkip - 1);
            index = next + 1;
          } else if (control == '\'') {
            if (next + 2 < length) {
              int value = hex(rtf.charAt(next + 1), rtf.charAt(next + 2));
              if (value >= 0 && !skipping && pendingSkip == 0) {
                // RTF hex escapes are code-page bytes; cp1252 and Latin-1
                // agree on everything mail bodies actually use.
                text.append((char) value);
              }
              pendingSkip = Math.max(0, pendingSkip - 1);
              index = next + 3;
            } else {
              index = length;
            }
          } else if (control == '*') {
            skipping = true;
            index = next + 1;
          } else if (Character.isLetter(control)) {
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

            if (SKIPPED_DESTINATIONS.contains(word)) {
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
                case "nbsp" -> text.append(' ');
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
          if (!skipping && pendingSkip == 0) {
            text.append(character);
          }
          pendingSkip = Math.max(0, pendingSkip - 1);
          index++;
        }
      }
    }
    return text.toString().strip();
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
