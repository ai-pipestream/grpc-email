package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.parse.RtfPlainText;
import org.junit.jupiter.api.Test;

/** The RTF fallback for .msg bodies that carry neither plain text nor HTML. */
class RtfPlainTextUnitTest {

  @Test
  void dropsControlWordsAndKeepsText() {
    assertThat(RtfPlainText.extract("{\\rtf1\\ansi\\deff0 Hello world}"))
        .isEqualTo("Hello world");
  }

  @Test
  void paragraphBreaksBecomeNewlines() {
    assertThat(RtfPlainText.extract("{\\rtf1 one\\par two}")).isEqualTo("one\ntwo");
  }

  @Test
  void skipsFontAndColourTables() {
    String rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0\\froman Times New Roman;}}"
        + "{\\colortbl;\\red0\\green0\\blue0;}\\f0 visible}";
    String text = RtfPlainText.extract(rtf);
    assertThat(text).isEqualTo("visible");
    assertThat(text).as("table contents are markup").doesNotContain("Times");
  }

  @Test
  void skipsStarDestinations() {
    assertThat(RtfPlainText.extract("{\\rtf1{\\*\\generator Riched20 10.0;}kept}"))
        .isEqualTo("kept");
  }

  @Test
  void decodesHexAndUnicodeEscapes() {
    assertThat(RtfPlainText.extract("{\\rtf1 caf\\'e9}")).isEqualTo("café");
    assertThat(RtfPlainText.extract("{\\rtf1 Gr\\u246?\\u223?e}")).isEqualTo("Größe");
  }

  @Test
  void honoursTheUnicodeSkipCount() {
    assertThat(RtfPlainText.extract("{\\rtf1\\uc2 \\u252??!}")).isEqualTo("ü!");
  }

  @Test
  void keepsBracesEscapedAsLiterals() {
    assertThat(RtfPlainText.extract("{\\rtf1 \\{literal\\}}")).isEqualTo("{literal}");
  }

  @Test
  void emptyAndNullInputAreEmpty() {
    assertThat(RtfPlainText.extract(null)).isEmpty();
    assertThat(RtfPlainText.extract("")).isEmpty();
  }

  @Test
  void unbalancedBracesDoNotThrow() {
    assertThat(RtfPlainText.extract("{\\rtf1 text")).contains("text");
    assertThat(RtfPlainText.extract("\\rtf1 text}}}}")).isEqualTo("text");
  }
}
