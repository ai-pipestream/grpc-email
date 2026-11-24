package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.parse.RtfPlainText;
import ai.pipestream.email.v1.BodyPart;
import java.nio.charset.Charset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An RTF {@code \'hh} escape is a byte in a code page, not a Latin-1
 * character. The code page is the document's ({@code \ansicpg}, 1252 when
 * unsaid) unless the current font names its own ({@code \fcharset},
 * {@code \cpg}), and a double-byte code page spends two bytes on a character.
 * Read as Latin-1, Outlook's curly quotes and dashes become C1 control
 * characters and a Cyrillic, Greek or Japanese body is mojibake throughout.
 */
class RtfCodePageUnitTest {

  /** {@code text} as RTF hex escapes in {@code codePage}, every byte escaped. */
  private static String hex(String text, String codePage) {
    StringBuilder escaped = new StringBuilder();
    for (byte value : text.getBytes(Charset.forName(codePage))) {
      escaped.append(String.format("\\'%02x", value & 0xFF));
    }
    return escaped.toString();
  }

  // --- the document code page ---------------------------------------------

  @Test
  @DisplayName("Outlook's curly quotes, dash and euro are punctuation, not C1 controls")
  void windows1252Punctuation() {
    RtfPlainText.Extracted extracted = RtfPlainText.read(
        "{\\rtf1\\ansi\\ansicpg1252 It\\'92s \\'93quoted\\'94 \\'96 5 \\'80}");
    assertThat(extracted.text()).isEqualTo("It’s “quoted” – 5 €");
    assertThat(extracted.codePage()).isEqualTo(1252);
    assertThat(extracted.charset()).isEqualTo("windows-1252");
    assertThat(extracted.undecodableCodePages()).isEmpty();
  }

  @Test
  void noAnsicpg_defaultsToWindows1252() {
    RtfPlainText.Extracted extracted = RtfPlainText.read("{\\rtf1\\ansi don\\'92t}");
    assertThat(extracted.text()).isEqualTo("don’t");
    assertThat(extracted.codePage()).isEqualTo(1252);
  }

  @Test
  void cyrillicDocument() {
    RtfPlainText.Extracted extracted = RtfPlainText.read(
        "{\\rtf1\\ansi\\ansicpg1251 " + hex("Привет", "windows-1251") + ", мир}");
    assertThat(extracted.text()).isEqualTo("Привет, мир");
    assertThat(extracted.codePage()).isEqualTo(1251);
    assertThat(extracted.charset()).isEqualTo("windows-1251");
  }

  @Test
  void greekDocument() {
    assertThat(RtfPlainText.extract(
        "{\\rtf1\\ansi\\ansicpg1253 " + hex("Καλημέρα", "windows-1253") + "}"))
        .isEqualTo("Καλημέρα");
  }

  @Test
  void macDocument() {
    assertThat(RtfPlainText.extract("{\\rtf1\\mac caf\\'8e}")).isEqualTo("café");
  }

  // --- double-byte code pages ------------------------------------------------

  @Test
  @DisplayName("a double-byte code page spends two escapes on one character")
  void japaneseDocument() {
    RtfPlainText.Extracted extracted = RtfPlainText.read(
        "{\\rtf1\\ansi\\ansicpg932 " + hex("日本語のメール", "windows-31j") + "}");
    assertThat(extracted.text()).isEqualTo("日本語のメール");
    assertThat(extracted.codePage()).isEqualTo(932);
  }

  @Test
  void simplifiedChineseDocument() {
    assertThat(RtfPlainText.extract(
        "{\\rtf1\\ansi\\ansicpg936 " + hex("你好，世界", "GBK") + "}"))
        .isEqualTo("你好，世界");
  }

  @Test
  @DisplayName("a trail byte written as a literal character still completes its lead byte")
  void doubleByteTrailAsLiteral() {
    // cp932: 0x83 0x41 is KATAKANA A; writers leave an ASCII trail byte bare.
    assertThat(RtfPlainText.extract("{\\rtf1\\ansi\\ansicpg932 \\'83A!}")).isEqualTo("ア!");
    // 0x83 0x5C is KATAKANA SO; the backslash trail byte arrives escaped.
    assertThat(RtfPlainText.extract("{\\rtf1\\ansi\\ansicpg932 \\'83\\\\ desu}"))
        .isEqualTo("ソ desu");
  }

  // --- fonts carry their own code page ---------------------------------------

  @Test
  @DisplayName("a run in a Cyrillic font is Cyrillic even in a Western document")
  void fontCharsetOverridesTheDocumentCodePage() {
    String rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0"
        + "{\\fonttbl{\\f0\\fswiss\\fcharset0 Arial;}{\\f1\\fswiss\\fcharset204 Arial CYR;}}"
        + "\\f0 caf\\'e9 \\f1 " + hex("Привет", "windows-1251") + "}";
    RtfPlainText.Extracted extracted = RtfPlainText.read(rtf);
    assertThat(extracted.text()).isEqualTo("café Привет");
    assertThat(extracted.codePage()).as("the document's own").isEqualTo(1252);
  }

  @Test
  void fontSwitchEndsWithItsGroup() {
    String rtf = "{\\rtf1\\ansi\\ansicpg1252"
        + "{\\fonttbl{\\f0\\fcharset0 Arial;}{\\f1\\fcharset161 Arial Greek;}}"
        + "\\f0 {\\f1 " + hex("Ω", "windows-1253") + "} \\'e9}";
    assertThat(RtfPlainText.extract(rtf)).isEqualTo("Ω é");
  }

  @Test
  void fontCpgNamesACodePageDirectly() {
    String rtf = "{\\rtf1\\ansi\\ansicpg1252"
        + "{\\fonttbl{\\f0\\fcharset0 Arial;}{\\f3\\cpg1250 Some Font;}}"
        + "\\f3 " + hex("Łódź", "windows-1250") + "}";
    assertThat(RtfPlainText.extract(rtf)).isEqualTo("Łódź");
  }

  // --- what is not a code-page byte is untouched -------------------------------

  @Test
  void unicodeEscapesAndTheirFallbackBytes() {
    // ၕ is П; its fallback byte is skipped, not decoded a second time.
    assertThat(RtfPlainText.extract("{\\rtf1\\ansi\\ansicpg1251\\uc1 \\u1055\\'cf ok}"))
        .isEqualTo("П ok");
  }

  @Test
  void aRunIsNotCarriedAcrossAParagraphBreak() {
    assertThat(RtfPlainText.extract("{\\rtf1\\ansi\\ansicpg1251 \\'cf\\par\\'f0}"))
        .isEqualTo("П\nр");
  }

  // --- a code page nobody can decode ---------------------------------------

  @Test
  @DisplayName("an unknown code page keeps the bytes as Latin-1 and reports the code page")
  void unknownCodePage() {
    RtfPlainText.Extracted extracted =
        RtfPlainText.read("{\\rtf1\\ansi\\ansicpg64999 caf\\'e9}");
    assertThat(extracted.text()).isEqualTo("café");
    assertThat(extracted.codePage()).isEqualTo(64999);
    assertThat(extracted.charset()).isEqualTo("iso-8859-1");
    assertThat(extracted.undecodableCodePages()).containsExactly(64999);
  }

  @Test
  void unknownCodePage_withNoHexBytes_reportsNothing() {
    RtfPlainText.Extracted extracted =
        RtfPlainText.read("{\\rtf1\\ansi\\ansicpg64999 plain}");
    assertThat(extracted.text()).isEqualTo("plain");
    assertThat(extracted.undecodableCodePages()).isEmpty();
  }

  // --- through the parsers -------------------------------------------------

  @Test
  void msgRtfBody_usesTheCodePage_andSaysWhichOnTheBodyPart() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.rtfOnly(
        "{\\rtf1\\ansi\\ansicpg1251 " + hex("Привет", "windows-1251") + "}"));
    BodyPart body = parsed.bodies.get(0);
    assertThat(body.getText()).isEqualTo("Привет");
    assertThat(body.getCharset()).isEqualTo("windows-1251");
    assertThat(body.getCharsetDetected()).isEqualTo("windows-1251");
    assertThat(body.getCharsetFallback()).isFalse();
    assertThat(parsed.warnings).noneMatch(warning -> warning.contains("code page"));
  }

  @Test
  void msgRtfBody_unknownCodePage_isFlaggedAndWarned() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.rtfOnly(
        "{\\rtf1\\ansi\\ansicpg64999 caf\\'e9}"));
    BodyPart body = parsed.bodies.get(0);
    assertThat(body.getText()).isEqualTo("café");
    assertThat(body.getCharset()).isEqualTo("cp64999");
    assertThat(body.getCharsetDetected()).isEqualTo("iso-8859-1");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(parsed.warnings).anyMatch(warning ->
        warning.contains("code page 64999") && warning.contains("iso-8859-1"));
  }

  @Test
  void tnefRtfBody_usesTheCodePage() throws Exception {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.tnefOnly(TnefFixtures.rtfBody(
        "{\\rtf1\\ansi\\ansicpg1252 It\\'92s here}")));
    BodyPart body = parsed.bodies.get(0);
    assertThat(body.getText()).isEqualTo("It’s here");
    assertThat(body.getCharsetDetected()).isEqualTo("windows-1252");
    assertThat(body.getCharsetFallback()).isFalse();
  }
}
