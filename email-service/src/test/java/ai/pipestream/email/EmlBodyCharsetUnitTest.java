package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Body text is decoded here, strictly, from the part's transfer-decoded
 * bytes. A body whose bytes do not match its charset (none declared, the
 * wrong one declared, one nobody knows) must come out readable where it can,
 * and must always say what happened in typed fields and a warning: never
 * mojibake under the declared charset with a clean trailer.
 */
class EmlBodyCharsetUnitTest {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  private static final String GERMAN = "Grüße aus Köln, bis später";

  /** A single-part message with {@code contentType} and a raw 8-bit body. */
  private static byte[] message(String contentType, byte[] body) {
    return message(contentType, "8bit", body);
  }

  private static byte[] message(String contentType, String transferEncoding, byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String head = "From: sender@example.com\r\n"
        + "To: reader@example.com\r\n"
        + "Subject: charset\r\n"
        + "MIME-Version: 1.0\r\n"
        + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
        + "Content-Transfer-Encoding: " + transferEncoding + "\r\n"
        + "\r\n";
    out.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(body);
    return out.toByteArray();
  }

  private static BodyPart onlyBody(RecordingSink parsed) {
    assertThat(parsed.bodies).hasSize(1);
    return parsed.bodies.get(0);
  }

  // --- the declared charset is right: nothing to report ---------------------

  @Test
  void declaredUtf8_validBytes_isACleanDecode() {
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain; charset=UTF-8", GERMAN.getBytes(StandardCharsets.UTF_8)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(GERMAN);
    assertThat(body.getCharset()).isEqualTo("UTF-8");
    assertThat(body.getCharsetDetected()).isEqualTo("utf-8");
    assertThat(body.getCharsetFallback()).isFalse();
    assertThat(body.getUndecodableSequences()).isZero();
    assertThat(parsed.warnings).isEmpty();
  }

  @Test
  void declaredLatin1_latin1Bytes_isACleanDecode() {
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain; charset=iso-8859-1", GERMAN.getBytes(StandardCharsets.ISO_8859_1)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(GERMAN);
    assertThat(body.getCharsetDetected()).isEqualTo("iso-8859-1");
    assertThat(body.getCharsetFallback()).isFalse();
    assertThat(parsed.warnings).isEmpty();
  }

  @Test
  void noCharset_plainAscii_isUsAsciiWithNothingToReport() {
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain", "plain old text".getBytes(StandardCharsets.US_ASCII)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo("plain old text");
    assertThat(body.getCharset()).isEmpty();
    assertThat(body.getCharsetDetected()).isEqualTo("us-ascii");
    assertThat(body.getCharsetFallback()).isFalse();
    assertThat(parsed.warnings).isEmpty();
  }

  @Test
  void noContentTypeAtAll_plainAscii_isUsAscii() {
    RecordingSink parsed = RecordingSink.eml(
        message(null, "7bit", "cron says hello".getBytes(StandardCharsets.US_ASCII)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo("cron says hello");
    assertThat(body.getCharsetDetected()).isEqualTo("us-ascii");
    assertThat(parsed.warnings).isEmpty();
  }

  // --- no charset declared, 8-bit bytes ------------------------------------

  @Test
  @DisplayName("8-bit UTF-8 with no charset parameter is read as UTF-8 and says so")
  void noCharset_utf8Bytes_isReadAsUtf8() {
    String text = GERMAN + " — 東京";
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain", text.getBytes(StandardCharsets.UTF_8)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).as("no mojibake, no U+FFFD").isEqualTo(text);
    assertThat(body.getCharset()).as("the part declared none").isEmpty();
    assertThat(body.getCharsetDetected()).isEqualTo("utf-8");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(body.getUndecodableSequences()).isZero();
    assertThat(parsed.warnings).singleElement().satisfies(warning ->
        assertThat(warning).contains("part 1").contains("no charset").contains("utf-8"));
  }

  @Test
  void noCharset_windows1252Bytes_isReadAsWindows1252() {
    String text = "café “quoted” – 5 €";
    RecordingSink parsed = RecordingSink.eml(message("text/plain", text.getBytes(WINDOWS_1252)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(text);
    assertThat(body.getCharsetDetected()).isEqualTo("windows-1252");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(parsed.warnings).singleElement().satisfies(warning ->
        assertThat(warning).contains("windows-1252"));
  }

  @Test
  void noCharset_bytesNoKnownCharsetExplains_keepEveryByteAsLatin1() {
    // 0x81 and 0x8D are undefined in windows-1252 and this is not UTF-8.
    byte[] bytes = {'a', (byte) 0x81, 'b', (byte) 0x8D, (byte) 0xE9, 'c'};
    RecordingSink parsed = RecordingSink.eml(message("text/plain", bytes));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(new String(bytes, StandardCharsets.ISO_8859_1));
    assertThat(body.getCharsetDetected()).isEqualTo("iso-8859-1");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(parsed.warnings).hasSize(1);
  }

  // --- the declared charset is wrong for the bytes -----------------------------

  @Test
  @DisplayName("declares utf-8 but carries windows-1252: readable, flagged, warned")
  void declaredUtf8_windows1252Bytes_fallsBack() {
    String text = "résumé “final” – 100 €";
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain; charset=utf-8", text.getBytes(WINDOWS_1252)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).as("no U+FFFD").isEqualTo(text);
    assertThat(body.getCharset()).as("what the part declared").isEqualTo("utf-8");
    assertThat(body.getCharsetDetected()).isEqualTo("windows-1252");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(body.getUndecodableSequences()).isZero();
    assertThat(parsed.warnings).singleElement().satisfies(warning ->
        assertThat(warning).contains("part 1").contains("utf-8").contains("windows-1252"));
  }

  @Test
  void declaredUsAscii_utf8Bytes_fallsBackToUtf8() {
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain; charset=us-ascii", GERMAN.getBytes(StandardCharsets.UTF_8)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(GERMAN);
    assertThat(body.getCharset()).isEqualTo("us-ascii");
    assertThat(body.getCharsetDetected()).isEqualTo("utf-8");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(parsed.warnings).hasSize(1);
  }

  @Test
  void declaredUtf8_htmlPart_fallsBackTheSameWay() {
    String html = "<p>déjà vu</p>";
    RecordingSink parsed = RecordingSink.eml(
        message("text/html; charset=utf-8", html.getBytes(WINDOWS_1252)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getMediaType()).isEqualTo(BodyMediaType.BODY_MEDIA_TYPE_HTML);
    assertThat(body.getText()).isEqualTo(html);
    assertThat(body.getCharsetDetected()).isEqualTo("windows-1252");
    assertThat(body.getCharsetFallback()).isTrue();
  }

  @Test
  void base64Body_isCheckedAfterTransferDecoding() {
    String text = "naïve café";
    byte[] encoded = Base64.getMimeEncoder().encode(text.getBytes(WINDOWS_1252));
    RecordingSink parsed = RecordingSink.eml(
        message("text/plain; charset=utf-8", "base64", encoded));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(text);
    assertThat(body.getCharsetDetected()).isEqualTo("windows-1252");
    assertThat(body.getCharsetFallback()).isTrue();
  }

  // --- mostly valid in the declared charset: keep it, count the damage ---------

  @Test
  @DisplayName("a UTF-8 body with one damaged byte stays UTF-8 and counts the damage")
  void declaredUtf8_oneDamagedSequence_keepsUtf8AndCountsIt() {
    byte[] good = (GERMAN + " und Grüße nach Düsseldorf").getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(good);
    bytes.write(0xC3); // the first byte of a two-byte sequence, cut off
    bytes.writeBytes(" Ende".getBytes(StandardCharsets.US_ASCII));
    RecordingSink parsed =
        RecordingSink.eml(message("text/plain; charset=utf-8", bytes.toByteArray()));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText())
        .as("the rest of the text is not re-read as another charset")
        .isEqualTo(GERMAN + " und Grüße nach Düsseldorf� Ende");
    assertThat(body.getCharsetDetected()).isEqualTo("utf-8");
    assertThat(body.getCharsetFallback()).isFalse();
    assertThat(body.getUndecodableSequences()).isEqualTo(1);
    assertThat(parsed.warnings).singleElement().satisfies(warning ->
        assertThat(warning).contains("part 1").contains("1 byte sequence").contains("U+FFFD"));
  }

  @Test
  void declaredGb2312_fewDamagedBytes_keepsTheDeclaredCharset() {
    Charset gb2312 = Charset.forName("GB2312");
    String text = "这是一封测试邮件，内容是中文。";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(text.getBytes(gb2312));
    bytes.write(0xFF); // never valid in GB2312
    RecordingSink parsed =
        RecordingSink.eml(message("text/plain; charset=gb2312", bytes.toByteArray()));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).startsWith(text);
    assertThat(body.getCharset()).isEqualTo("gb2312");
    // "gb2312" mail is decoded as its superset, the way mail clients do.
    assertThat(body.getCharsetDetected()).isEqualTo("gb18030");
    assertThat(body.getCharsetFallback()).isFalse();
    assertThat(body.getUndecodableSequences()).isEqualTo(1);
    assertThat(parsed.warnings).hasSize(1);
  }

  // --- a charset nobody knows ----------------------------------------------

  @Test
  void unknownCharset_utf8Bytes_isReadAsUtf8() {
    RecordingSink parsed = RecordingSink.eml(message(
        "text/plain; charset=x-not-a-real-charset", GERMAN.getBytes(StandardCharsets.UTF_8)));
    BodyPart body = onlyBody(parsed);
    assertThat(body.getText()).isEqualTo(GERMAN);
    assertThat(body.getCharset()).as("what the part declared").isEqualTo("x-not-a-real-charset");
    assertThat(body.getCharsetDetected()).isEqualTo("utf-8");
    assertThat(body.getCharsetFallback()).isTrue();
    assertThat(parsed.warnings).singleElement().satisfies(warning ->
        assertThat(warning).contains("x-not-a-real-charset").contains("utf-8"));
  }

  // --- one bad part does not taint the others ----------------------------------

  @Test
  void multipart_onlyTheMismatchedPartIsFlagged() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(("From: a@example.com\r\nSubject: two\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: multipart/alternative; boundary=b1\r\n\r\n"
        + "--b1\r\nContent-Type: text/plain; charset=utf-8\r\n"
        + "Content-Transfer-Encoding: 8bit\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(GERMAN.getBytes(StandardCharsets.UTF_8));
    out.writeBytes(("\r\n--b1\r\nContent-Type: text/html; charset=utf-8\r\n"
        + "Content-Transfer-Encoding: 8bit\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.writeBytes("<p>Grüße</p>".getBytes(WINDOWS_1252));
    out.writeBytes("\r\n--b1--\r\n".getBytes(StandardCharsets.US_ASCII));

    RecordingSink parsed = RecordingSink.eml(out.toByteArray());
    assertThat(parsed.bodies).hasSize(2);
    assertThat(parsed.bodies.get(0).getText()).isEqualTo(GERMAN);
    assertThat(parsed.bodies.get(0).getCharsetFallback()).isFalse();
    assertThat(parsed.bodies.get(1).getText()).isEqualTo("<p>Grüße</p>");
    assertThat(parsed.bodies.get(1).getCharsetFallback()).isTrue();
    assertThat(parsed.bodies.get(1).getCharsetDetected()).isEqualTo("windows-1252");
    assertThat(parsed.warnings).singleElement().satisfies(warning ->
        assertThat(warning).contains("part 1.2"));
  }
}
