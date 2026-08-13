package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import com.google.protobuf.ByteString;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Walks an RFC 822 MIME tree and streams each leaf the moment it is decoded.
 * The envelope is not produced here: the server already emitted EmailInfo
 * from the header block while the body was still uploading, and re-deriving
 * it would risk two different answers to the same question.
 *
 * <p>Events follow MIME document order. For the shapes real mail uses --
 * text/plain, multipart/alternative, multipart/mixed with trailing
 * attachments -- that is exactly body parts then attachments.
 */
public final class EmlParser {

  /**
   * Jakarta Mail defaults abort on the malformed mail that real mailboxes
   * are full of. Every relaxation here turns a hard failure into a decoded
   * part; none of them invent content.
   */
  private static final Properties LENIENT = lenientProperties();

  private EmlParser() {}

  private static Properties lenientProperties() {
    Properties properties = new Properties();
    properties.setProperty("mail.mime.address.strict", "false");
    properties.setProperty("mail.mime.decodetext.strict", "false");
    properties.setProperty("mail.mime.decodefilename", "true");
    properties.setProperty("mail.mime.multipart.allowempty", "true");
    properties.setProperty("mail.mime.multipart.ignoremissingendboundary", "true");
    properties.setProperty("mail.mime.base64.ignoreerrors", "true");
    properties.setProperty("mail.mime.ignoreunknownencoding", "true");
    properties.setProperty("mail.mime.uudecode.ignoreerrors", "true");
    properties.setProperty("mail.mime.parameters.strict", "false");
    return properties;
  }

  /** Parses the message body, streaming body parts and attachments to the sink. */
  public static void parse(byte[] bytes, ParseOptions options, ParseSink sink) {
    MimeMessage message;
    try {
      message = new MimeMessage(
          Session.getInstance(LENIENT), new ByteArrayInputStream(bytes));
    } catch (MessagingException unreadable) {
      throw new InvalidEmailException(
          "unreadable RFC 822 message: " + unreadable.getMessage(), unreadable);
    }
    Counter attachments = new Counter();
    walk(message, "1", options, sink, attachments);
  }

  /** Mutable attachment index, threaded through the recursive walk. */
  private static final class Counter {
    private int value;
  }

  private static void walk(
      Part part, String path, ParseOptions options, ParseSink sink, Counter attachments) {
    if (isMultipart(part)) {
      Multipart multipart;
      try {
        multipart = (Multipart) part.getContent();
      } catch (IOException | MessagingException broken) {
        throw new InvalidEmailException(
            "truncated multipart at " + path + ": " + broken.getMessage(), broken);
      }
      int count;
      try {
        count = multipart.getCount();
      } catch (MessagingException broken) {
        throw new InvalidEmailException(
            "unreadable multipart at " + path + ": " + broken.getMessage(), broken);
      }
      for (int index = 0; index < count; index++) {
        try {
          walk(multipart.getBodyPart(index), path + "." + (index + 1), options, sink, attachments);
        } catch (MessagingException broken) {
          sink.warn("part " + path + "." + (index + 1) + " skipped: " + broken.getMessage());
        }
      }
      return;
    }
    emitLeaf(part, path, options, sink, attachments);
  }

  private static void emitLeaf(
      Part part, String path, ParseOptions options, ParseSink sink, Counter attachments) {
    String contentType = header(part, "Content-Type", "text/plain");
    String baseType = HeaderProjection.baseType(contentType);
    String disposition = disposition(part);
    String filename = filename(part);
    boolean forcedAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition);
    boolean textual = baseType.equals("text/plain") || baseType.equals("text/html");

    if (textual && !forcedAttachment && filename.isEmpty()) {
      emitBody(part, path, baseType, contentType, sink);
      return;
    }
    if (baseType.equals("message/rfc822")) {
      sink.warn("part " + path
          + " is a nested message/rfc822; emitted as an attachment for the coordinator to reparse");
    }
    emitAttachment(part, path, baseType, filename, disposition, options, sink, attachments);
  }

  private static void emitBody(
      Part part, String path, String baseType, String contentType, ParseSink sink) {
    String charset = parameter(contentType, "charset");
    Decoded decoded = text(part, charset, path, sink);
    sink.bodyPart(
        BodyPart.newBuilder()
            .setPartId(path)
            .setMediaType(baseType.equals("text/html")
                ? BodyMediaType.BODY_MEDIA_TYPE_HTML
                : BodyMediaType.BODY_MEDIA_TYPE_PLAIN)
            .setContentTypeRaw(baseType)
            .setText(decoded.text())
            .setCharset(decoded.charset())
            .build());
  }

  private static void emitAttachment(
      Part part,
      String path,
      String baseType,
      String filename,
      String disposition,
      ParseOptions options,
      ParseSink sink,
      Counter attachments) {
    byte[] payload = payload(part, path, sink);
    int index = attachments.value++;
    if (filename.isEmpty()) {
      sink.warn("attachment " + index + " at part " + path + " has no filename");
    }
    Attachment.Builder attachment = Attachment.newBuilder()
        .setIndex(index)
        .setPartId(path)
        .setFilename(filename)
        .setContentType(baseType)
        .setSizeBytes(payload.length)
        .setContentId(HeaderProjection.stripAngles(header(part, "Content-ID", "")))
        .setInline(Part.INLINE.equalsIgnoreCase(disposition));
    if (options.includeAttachmentBytes()) {
      if (payload.length <= options.maxAttachmentBytes()) {
        attachment.setData(ByteString.copyFrom(payload));
      } else {
        sink.warn("attachment " + index + " (" + payload.length
            + " bytes) exceeds the per-attachment cap; described without its bytes");
      }
    }
    sink.attachment(attachment.build());
  }

  /** A decoded body part plus the charset actually used to decode it. */
  private record Decoded(String text, String charset) {}

  private static Decoded text(Part part, String declaredCharset, String path, ParseSink sink) {
    try {
      Object content = part.getContent();
      if (content instanceof String string) {
        return new Decoded(string, declaredCharset);
      }
      if (content instanceof InputStream stream) {
        try (InputStream open = stream) {
          return new Decoded(new String(open.readAllBytes(), StandardCharsets.UTF_8),
              declaredCharset);
        }
      }
      return new Decoded(String.valueOf(content), declaredCharset);
    } catch (IOException | MessagingException | RuntimeException undecodable) {
      // An unknown or lying charset must not lose the text. ISO-8859-1 maps
      // every byte to a code point, so the content survives round-tripping.
      sink.warn("part " + path + " declared charset '" + declaredCharset
          + "' which could not be decoded; read as ISO-8859-1");
      return new Decoded(raw(part, path, StandardCharsets.ISO_8859_1), "iso-8859-1");
    }
  }

  private static String raw(Part part, String path, java.nio.charset.Charset charset) {
    try (InputStream stream = part.getInputStream()) {
      return new String(stream.readAllBytes(), charset);
    } catch (IOException | MessagingException unreadable) {
      throw new InvalidEmailException(
          "unreadable body at part " + path + ": " + unreadable.getMessage(), unreadable);
    }
  }

  private static byte[] payload(Part part, String path, ParseSink sink) {
    try (InputStream stream = part.getInputStream()) {
      return stream.readAllBytes();
    } catch (IOException | MessagingException unreadable) {
      sink.warn("attachment payload at part " + path + " is unreadable: "
          + unreadable.getMessage());
      return new byte[0];
    }
  }

  private static boolean isMultipart(Part part) {
    try {
      return part.isMimeType("multipart/*");
    } catch (MessagingException unreadable) {
      return false;
    }
  }

  private static String disposition(Part part) {
    try {
      String value = part.getDisposition();
      return value == null ? "" : value.trim();
    } catch (MessagingException unreadable) {
      return "";
    }
  }

  private static String filename(Part part) {
    try {
      String value = part.getFileName();
      return value == null ? "" : HeaderProjection.decoded(value).trim();
    } catch (MessagingException unreadable) {
      return "";
    }
  }

  private static String header(Part part, String name, String fallback) {
    try {
      String[] values = part instanceof Message message
          ? message.getHeader(name)
          : part.getHeader(name);
      if (values == null || values.length == 0 || values[0] == null) {
        return fallback;
      }
      return values[0];
    } catch (MessagingException unreadable) {
      return fallback;
    }
  }

  private static String parameter(String contentType, String name) {
    try {
      String value = new ContentType(contentType).getParameter(name);
      return value == null ? "" : value.trim();
    } catch (Exception unparseable) {
      return "";
    }
  }
}
