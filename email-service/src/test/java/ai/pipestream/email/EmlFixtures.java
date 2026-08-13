package ai.pipestream.email;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

/**
 * Builds .eml bytes with Jakarta Mail itself, so nothing binary is committed
 * and every assertion is against content this file placed.
 */
final class EmlFixtures {

  static final String SUBJECT = "Rückfrage zur Anhörung";
  static final String FROM_NAME = "Clerk of Court";
  static final String FROM_EMAIL = "clerk@example.gov";
  static final String TO_EMAIL = "ada@example.com";
  static final String CC_EMAIL = "bob@example.com";
  static final String MESSAGE_ID = "eml-0001@example.gov";
  static final String IN_REPLY_TO = "parent-0000@example.com";
  static final String PLAIN_BODY = "Die Anhörung beginnt um 09:00 Uhr.";
  static final String HTML_BODY = "<html><body><p>Die Anh&ouml;rung beginnt um 09:00 Uhr.</p></body></html>";
  static final String ATTACHMENT_NAME = "order.pdf";
  static final byte[] ATTACHMENT_BYTES =
      "%PDF-1.4 not really a pdf".getBytes(StandardCharsets.US_ASCII);
  static final String INLINE_NAME = "seal.png";
  static final byte[] INLINE_BYTES = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
  static final String INLINE_CONTENT_ID = "seal@example.gov";
  static final long SENT_MILLIS = 1_700_000_000_000L;

  private EmlFixtures() {}

  private static Session session() {
    return Session.getInstance(new Properties());
  }

  /** A plain single-part message: headers plus one text/plain body. */
  static byte[] plainText() throws Exception {
    MimeMessage message = envelope();
    message.setText(PLAIN_BODY, "UTF-8");
    return bytes(message);
  }

  /**
   * multipart/mixed of a multipart/alternative body (plain + HTML), one
   * attachment, and one inline part carrying a Content-ID.
   */
  static byte[] multipartWithAttachments() throws Exception {
    MimeMessage message = envelope();

    MimeMultipart alternative = new MimeMultipart("alternative");
    MimeBodyPart plain = new MimeBodyPart();
    plain.setText(PLAIN_BODY, "UTF-8");
    alternative.addBodyPart(plain);
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(HTML_BODY, "text/html; charset=UTF-8");
    alternative.addBodyPart(html);

    MimeBodyPart alternativeHolder = new MimeBodyPart();
    alternativeHolder.setContent(alternative);

    MimeBodyPart attachment = new MimeBodyPart();
    attachment.setDataHandler(new DataHandler(
        new ByteArrayDataSource(ATTACHMENT_BYTES, "application/pdf")));
    attachment.setFileName(ATTACHMENT_NAME);
    attachment.setDisposition(Message.ATTACHMENT);

    MimeBodyPart inline = new MimeBodyPart();
    inline.setDataHandler(new DataHandler(new ByteArrayDataSource(INLINE_BYTES, "image/png")));
    inline.setFileName(INLINE_NAME);
    inline.setDisposition(Message.INLINE);
    inline.setHeader("Content-ID", "<" + INLINE_CONTENT_ID + ">");

    MimeMultipart mixed = new MimeMultipart("mixed");
    mixed.addBodyPart(alternativeHolder);
    mixed.addBodyPart(attachment);
    mixed.addBodyPart(inline);
    message.setContent(mixed);
    return bytes(message);
  }

  /**
   * A message whose text/plain part declares a charset that does not exist.
   * Written by hand because Jakarta Mail refuses to serialize one.
   */
  static byte[] bogusCharset() {
    return ("From: " + FROM_EMAIL + "\r\n"
        + "To: " + TO_EMAIL + "\r\n"
        + "Subject: bad charset\r\n"
        + "Message-ID: <" + MESSAGE_ID + ">\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=x-not-a-real-charset\r\n"
        + "\r\n"
        + "the bytes are still readable\r\n").getBytes(StandardCharsets.US_ASCII);
  }

  /** An attachment part with no filename, which must warn rather than fail. */
  static byte[] unnamedAttachment() throws Exception {
    MimeMessage message = envelope();
    MimeBodyPart body = new MimeBodyPart();
    body.setText(PLAIN_BODY, "UTF-8");
    MimeBodyPart nameless = new MimeBodyPart();
    nameless.setDataHandler(new DataHandler(
        new ByteArrayDataSource(ATTACHMENT_BYTES, "application/octet-stream")));
    MimeMultipart mixed = new MimeMultipart("mixed");
    mixed.addBodyPart(body);
    mixed.addBodyPart(nameless);
    message.setContent(mixed);
    return bytes(message);
  }

  /** Headers with no terminating empty line: a message cut off mid-upload. */
  static byte[] truncatedHeaderBlock() {
    return ("From: " + FROM_EMAIL + "\r\nSubject: cut short\r\nTo: " + TO_EMAIL + "\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  /** A header-shaped block whose fields are not mail fields. */
  static byte[] notMailButColonShaped() {
    return "key: value\r\nother: thing\r\n\r\nbody text\r\n".getBytes(StandardCharsets.US_ASCII);
  }

  /** Index just past the empty line that ends the header block. */
  static int headerBlockEnd(byte[] message) {
    for (int index = 0; index + 3 < message.length; index++) {
      if (message[index] == '\r' && message[index + 1] == '\n'
          && message[index + 2] == '\r' && message[index + 3] == '\n') {
        return index + 4;
      }
    }
    throw new IllegalArgumentException("fixture has no header block terminator");
  }

  private static MimeMessage envelope() throws Exception {
    MimeMessage message = new MimeMessage(session());
    message.setFrom(new InternetAddress(FROM_EMAIL, FROM_NAME, "UTF-8"));
    message.setRecipients(Message.RecipientType.TO, TO_EMAIL);
    message.setRecipients(Message.RecipientType.CC, CC_EMAIL);
    message.setSubject(SUBJECT, "UTF-8");
    message.setSentDate(new Date(SENT_MILLIS));
    message.setHeader("In-Reply-To", "<" + IN_REPLY_TO + ">");
    message.setHeader("References", "<root-0000@example.com> <" + IN_REPLY_TO + ">");
    return message;
  }

  private static byte[] bytes(MimeMessage message) throws Exception {
    message.saveChanges();
    // saveChanges() stamps a random Message-ID; a fixture needs a stable one.
    message.setHeader("Message-ID", "<" + MESSAGE_ID + ">");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    message.writeTo(out);
    return out.toByteArray();
  }
}
