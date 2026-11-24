package ai.pipestream.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.email.v1.BodyPart;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A MAPI HTML body is stored as bytes in a code page. Those bytes used to
 * go through a replacing decoder, and a body with no stored code page was
 * read as windows-1252 in a .msg and as UTF-8 in a TNEF container, with
 * nothing on the BodyPart or the trailer to say a guess had been made.
 */
class MapiHtmlCodePageUnitTest {

  private static final String HTML = "<p>Grüße aus Köln, €5</p>";
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

  // --- .msg -----------------------------------------------------------------

  @Test
  void msgHtml_inItsStoredCodePage_isReadAndNamed() throws Exception {
    RecordingSink parsed = RecordingSink.msg(
        MsgFixtures.htmlBytes(HTML.getBytes(StandardCharsets.UTF_8), 65001));

    BodyPart html = only(parsed);
    assertEquals(HTML, html.getText());
    assertEquals("utf-8", html.getCharset());
    assertEquals("utf-8", html.getCharsetDetected());
    assertFalse(html.getCharsetFallback());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void msgHtml_inACyrillicCodePage_isRead() throws Exception {
    String russian = "<p>Привет</p>";
    RecordingSink parsed = RecordingSink.msg(
        MsgFixtures.htmlBytes(russian.getBytes(WINDOWS_1251), 1251));

    BodyPart html = only(parsed);
    assertEquals(russian, html.getText());
    assertEquals("windows-1251", html.getCharsetDetected());
    assertFalse(html.getCharsetFallback());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void msgHtml_utf8WithNoStoredCodePage_isReadAsUtf8_flagged_andWarned() throws Exception {
    RecordingSink parsed = RecordingSink.msg(
        MsgFixtures.htmlBytes(HTML.getBytes(StandardCharsets.UTF_8), 0));

    BodyPart html = only(parsed);
    assertEquals(HTML, html.getText(), "not windows-1252 mojibake");
    assertEquals("", html.getCharset(), "nothing was stored");
    assertEquals("utf-8", html.getCharsetDetected());
    assertTrue(html.getCharsetFallback());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().contains("body:html"), parsed.warnings.getFirst());
  }

  @Test
  void msgHtml_asciiWithNoStoredCodePage_needsNoGuess() throws Exception {
    RecordingSink parsed = RecordingSink.msg(
        MsgFixtures.htmlBytes("<p>plain</p>".getBytes(StandardCharsets.US_ASCII), 0));

    BodyPart html = only(parsed);
    assertEquals("<p>plain</p>", html.getText());
    assertEquals("us-ascii", html.getCharsetDetected());
    assertFalse(html.getCharsetFallback());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void msgHtml_thatDoesNotFitItsStoredCodePage_isFlagged_andWarned() throws Exception {
    // Stored as UTF-8, bytes are windows-1252: 0xFC is not valid UTF-8.
    RecordingSink parsed = RecordingSink.msg(
        MsgFixtures.htmlBytes(HTML.getBytes(WINDOWS_1252), 65001));

    BodyPart html = only(parsed);
    assertEquals(HTML, html.getText(), "no U+FFFD where the letters were");
    assertEquals("utf-8", html.getCharset());
    assertEquals("windows-1252", html.getCharsetDetected());
    assertTrue(html.getCharsetFallback());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void msgHtml_inACodePageJavaDoesNotKnow_isFlagged_andWarned() throws Exception {
    RecordingSink parsed = RecordingSink.msg(
        MsgFixtures.htmlBytes(HTML.getBytes(StandardCharsets.UTF_8), 65000));

    BodyPart html = only(parsed);
    assertEquals("cp65000", html.getCharset());
    assertTrue(html.getCharsetFallback());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().contains("cp65000"), parsed.warnings.getFirst());
  }

  // --- TNEF -----------------------------------------------------------------

  @Test
  void tnefHtml_inItsStoredCodePage_isReadAndNamed() throws Exception {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.tnefOnly(
        TnefFixtures.htmlBytes(HTML.getBytes(WINDOWS_1252), 1252)));

    BodyPart html = only(parsed);
    assertEquals(HTML, html.getText());
    assertEquals("windows-1252", html.getCharset());
    assertEquals("windows-1252", html.getCharsetDetected());
    assertFalse(html.getCharsetFallback());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void tnefHtml_windows1252WithNoStoredCodePage_keepsItsLetters_flagged_andWarned()
      throws Exception {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.tnefOnly(
        TnefFixtures.htmlBytes(HTML.getBytes(WINDOWS_1252), 0)));

    BodyPart html = only(parsed);
    assertEquals(HTML, html.getText(), "not U+FFFD from a UTF-8 guess");
    assertEquals("", html.getCharset());
    assertEquals("windows-1252", html.getCharsetDetected());
    assertTrue(html.getCharsetFallback());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().contains("1/body:html"), parsed.warnings.getFirst());
  }

  @Test
  void tnefHtml_thatDoesNotFitItsStoredCodePage_isFlagged_andWarned() throws Exception {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.tnefOnly(
        TnefFixtures.htmlBytes(HTML.getBytes(WINDOWS_1252), 65001)));

    BodyPart html = only(parsed);
    assertEquals(HTML, html.getText());
    assertEquals("utf-8", html.getCharset());
    assertEquals("windows-1252", html.getCharsetDetected());
    assertTrue(html.getCharsetFallback());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void tnefAttBody_thatDoesNotFitTheStreamCodePage_isFlagged_andWarned() throws Exception {
    // The stream says windows-1252, and the UTF-8 of a capital L with
    // stroke is 0xC5 0x81: 0x81 is not a character in windows-1252.
    String text = "Pozdrowienia z \u0141odzi";
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.tnefOnly(
        TnefFixtures.attBodyBytes(text.getBytes(StandardCharsets.UTF_8), 1252)));

    BodyPart plain = only(parsed);
    assertEquals("1/body:plain", plain.getPartId());
    assertEquals(text, plain.getText(), "valid UTF-8, read as UTF-8");
    assertEquals("windows-1252", plain.getCharset());
    assertEquals("utf-8", plain.getCharsetDetected());
    assertTrue(plain.getCharsetFallback());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void tnefAttBody_inTheStreamCodePage_isReadAndNamed() throws Exception {
    String text = "Grüße aus Köln";
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.tnefOnly(
        TnefFixtures.attBodyBytes(text.getBytes(WINDOWS_1252), 1252)));

    BodyPart plain = only(parsed);
    assertEquals(text, plain.getText());
    assertEquals("windows-1252", plain.getCharsetDetected());
    assertFalse(plain.getCharsetFallback());
    assertEquals(List.of(), parsed.warnings);
  }

  private static BodyPart only(RecordingSink parsed) {
    assertEquals(1, parsed.bodies.size(), parsed.bodies.toString());
    return parsed.bodies.getFirst();
  }
}
