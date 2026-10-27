package ai.pipestream.email.parse;

/**
 * Removes RFC 5322 line folding from a header value in one pass.
 *
 * <p>Jakarta Mail's {@code MimeUtility.unfold} copies the rest of the value
 * every time it removes a fold, so a value made of a few hundred thousand
 * continuation lines (under a megabyte of input) occupies the request
 * thread for most of a minute. This does the same job with one scan and
 * one copy, and matches that method's output, including its oddities: a
 * CR, LF or CRLF followed by a space, a tab or the end of the value is
 * removed; one followed by anything else is kept; and a backslash right
 * before a line break is dropped while the break itself stays.
 */
public final class HeaderFolding {

  private HeaderFolding() {}

  /** Unfolds one header value; a value with no line break is handed back as is. */
  public static String unfold(String value) {
    int length = value.length();
    int lineBreak = nextLineBreak(value, 0);
    if (lineBreak < 0) {
      return value;
    }
    StringBuilder out = new StringBuilder(length);
    int copied = 0;
    while (lineBreak >= 0) {
      int resume = afterLineBreak(value, lineBreak);
      if (lineBreak > 0 && value.charAt(lineBreak - 1) == '\\') {
        out.append(value, copied, lineBreak - 1).append(value, lineBreak, resume);
      } else if (resume >= length || isFoldingSpace(value.charAt(resume))) {
        out.append(value, copied, lineBreak);
      } else {
        out.append(value, copied, resume);
      }
      copied = resume;
      lineBreak = nextLineBreak(value, resume);
    }
    return out.append(value, copied, length).toString();
  }

  private static int nextLineBreak(String value, int from) {
    for (int index = from; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\r' || character == '\n') {
        return index;
      }
    }
    return -1;
  }

  /** Index just past the CR, LF or CRLF that starts at {@code lineBreak}. */
  private static int afterLineBreak(String value, int lineBreak) {
    int next = lineBreak + 1;
    if (next < value.length() && value.charAt(lineBreak) == '\r' && value.charAt(next) == '\n') {
      return next + 1;
    }
    return next;
  }

  private static boolean isFoldingSpace(char character) {
    return character == ' ' || character == '\t';
  }
}
