package ai.pipestream.email.parse;

/**
 * Finds the first delimiter in an address list that is never closed.
 *
 * <p>Jakarta Mail's lenient address parser, on meeting a {@code (}, {@code <}
 * or {@code [} with no closer, scans to the end of the value, gives up,
 * treats the opener as an ordinary character and carries on from the next
 * one. A value that is nothing but openers therefore costs one scan per
 * character, and 200 KB of them takes over a minute. This pass finds the
 * first such opener in linear time so the caller can cut the value there
 * and hand the parser only the part it can read without restarting.
 *
 * <p>The scan follows the parser's own rules for what closes each opener: a
 * comment nests and honours backslash escapes, an angle address skips
 * quoted strings inside it, a domain literal and a quoted string honour
 * backslash escapes only.
 */
final class MailboxDelimiters {

  private MailboxDelimiters() {}

  /**
   * Start of the mailbox that holds the first unclosed delimiter.
   *
   * @param value one unfolded, ungrouped address-list header value
   * @return the index just after the last top-level comma before that
   *     delimiter (0 when there is none), or -1 when every delimiter closes
   */
  static int unterminatedMailboxStart(String value) {
    int mailbox = 0;
    int index = 0;
    while (index < value.length()) {
      char character = value.charAt(index);
      int closer = switch (character) {
        case '(' -> commentEnd(value, index);
        case '<' -> angleEnd(value, index);
        case '[' -> closerOf(value, index, ']');
        case '"' -> closerOf(value, index, '"');
        default -> index;
      };
      if (closer < 0) {
        return mailbox;
      }
      if (character == ',') {
        mailbox = index + 1;
      }
      index = closer + 1;
    }
    return -1;
  }

  /** Index of the parenthesis that closes the comment opening at {@code open}, or -1. */
  private static int commentEnd(String value, int open) {
    int depth = 0;
    for (int index = open; index < value.length(); index++) {
      switch (value.charAt(index)) {
        case '\\' -> index++;
        case '(' -> depth++;
        case ')' -> {
          if (--depth == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }

  /** Index of the {@code >} that closes the angle address opening at {@code open}, or -1. */
  private static int angleEnd(String value, int open) {
    boolean quoted = false;
    for (int index = open + 1; index < value.length(); index++) {
      switch (value.charAt(index)) {
        case '\\' -> index++;
        case '"' -> quoted = !quoted;
        case '>' -> {
          if (!quoted) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }

  /** Index of the first unescaped {@code closer} after {@code open}, or -1. */
  private static int closerOf(String value, int open, char closer) {
    for (int index = open + 1; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\') {
        index++;
      } else if (character == closer) {
        return index;
      }
    }
    return -1;
  }
}
