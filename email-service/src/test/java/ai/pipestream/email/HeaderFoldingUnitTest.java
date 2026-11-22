package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.parse.HeaderFolding;
import jakarta.mail.internet.MimeUtility;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one-pass unfold replaces Jakarta Mail's, so it must give the same
 * answer the library gives, oddities included. Checking against the
 * library directly makes this the canary for an angus-mail upgrade that
 * changes what unfolding means.
 */
class HeaderFoldingUnitTest {

  private static final List<String> SAMPLES = List.of(
      "plain",
      "",
      "a\r\n b",
      "a\r\n\tb",
      "a\r\nb",
      "a\n b",
      "a\rb",
      "a\r b",
      "a\r\n",
      "a\r",
      "a\n",
      "a\r\n\t",
      "a\r\n \r\n b",
      "\r\n a",
      "a \r\n  b",
      "a\n\n b",
      "a\r\n\r\n b",
      "a\r\r b",
      "a\n\r b",
      "a\\\r\n b",
      "a\\\r\nb",
      "a\\\n b",
      "\\\r\n",
      "a\\\r\n\\\r\n b",
      "=?UTF-8?Q?a?=\r\n =?UTF-8?Q?b?=");

  @Test
  @DisplayName("every sample unfolds exactly as Jakarta Mail unfolds it")
  void matchesJakartaMailOnEverySample() {
    for (String sample : SAMPLES) {
      assertThat(HeaderFolding.unfold(sample))
          .as("unfold of %s", sample.replace("\r", "\\r").replace("\n", "\\n"))
          .isEqualTo(MimeUtility.unfold(sample));
    }
  }

  @Test
  void aValueWithNoLineBreakIsReturnedAsIs() {
    String value = "no folding here";
    assertThat(HeaderFolding.unfold(value)).isSameAs(value);
  }

  @Test
  @DisplayName("a quarter million continuation lines unfold in one pass")
  void aFloodOfContinuationLinesUnfoldsInLinearTime() {
    String value = "a\r\n ".repeat(249_999) + "a";
    long started = System.nanoTime();
    String unfolded = HeaderFolding.unfold(value);
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    assertThat(unfolded).hasSize(2 * 250_000 - 1).startsWith("a a a").endsWith("a a");
    assertThat(took).isLessThan(Duration.ofSeconds(2));
  }
}
