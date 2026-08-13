package ai.pipestream.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.email.parse.RtfPlainText;
import org.junit.jupiter.api.Test;

/** The RTF fallback for .msg bodies that carry neither plain text nor HTML. */
class RtfPlainTextUnitTest {

  @Test
  void dropsControlWordsAndKeepsText() {
    assertEquals("Hello world",
        RtfPlainText.extract("{\\rtf1\\ansi\\deff0 Hello world}"));
  }

  @Test
  void paragraphBreaksBecomeNewlines() {
    assertEquals("one\ntwo", RtfPlainText.extract("{\\rtf1 one\\par two}"));
  }

  @Test
  void skipsFontAndColourTables() {
    String rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0\\froman Times New Roman;}}"
        + "{\\colortbl;\\red0\\green0\\blue0;}\\f0 visible}";
    String text = RtfPlainText.extract(rtf);
    assertEquals("visible", text);
    assertFalse(text.contains("Times"), "table contents are markup");
  }

  @Test
  void skipsStarDestinations() {
    assertEquals("kept",
        RtfPlainText.extract("{\\rtf1{\\*\\generator Riched20 10.0;}kept}"));
  }

  @Test
  void decodesHexAndUnicodeEscapes() {
    assertEquals("café", RtfPlainText.extract("{\\rtf1 caf\\'e9}"));
    assertEquals("Größe", RtfPlainText.extract("{\\rtf1 Gr\\u246?\\u223?e}"));
  }

  @Test
  void honoursTheUnicodeSkipCount() {
    assertEquals("ü!", RtfPlainText.extract("{\\rtf1\\uc2 \\u252??!}"));
  }

  @Test
  void keepsBracesEscapedAsLiterals() {
    assertEquals("{literal}", RtfPlainText.extract("{\\rtf1 \\{literal\\}}"));
  }

  @Test
  void emptyAndNullInputAreEmpty() {
    assertEquals("", RtfPlainText.extract(null));
    assertEquals("", RtfPlainText.extract(""));
  }

  @Test
  void unbalancedBracesDoNotThrow() {
    assertTrue(RtfPlainText.extract("{\\rtf1 text").contains("text"));
    assertEquals("text", RtfPlainText.extract("\\rtf1 text}}}}"));
  }
}
