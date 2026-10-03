package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import com.google.protobuf.ByteString;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimePartDataSource;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
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
   * The Session-scoped leniency. Jakarta Mail defaults abort on the
   * malformed mail real mailboxes are full of, but it reads almost every
   * relaxation from System properties, several of them only once, when the
   * class that uses them first loads; only {@code mail.mime.address.strict}
   * is read from the Session. The rest are applied where this parser
   * controls them, one part at a time, so nothing is global and a repair
   * can be reported instead of silently made:
   *
   * <ul>
   *   <li>{@code mail.mime.multipart.*}: {@link LenientMultipart}.
   *   <li>{@code mail.mime.base64.ignoreerrors}, {@code
   *       mail.mime.uudecode.ignoreerrors}, {@code
   *       mail.mime.ignoreunknownencoding}: {@link TransferDecoding}, after
   *       the strict decoder refuses a part.
   *   <li>{@code mail.mime.decodetext.strict}: {@link HeaderProjection#decoded}.
   *   <li>{@code mail.mime.parameters.strict}: {@link #lenientParameter}.
   *   <li>{@code mail.mime.decodefilename}: {@link #filename} decodes.
   * </ul>
   *
   * <p>Every relaxation turns a hard failure into a decoded part; none of
   * them invent content.
   */
  private static final Properties LENIENT = lenientProperties();

  /**
   * How many multiparts deep the walk descends. Real mail nests a handful
   * (mixed, related, alternative, perhaps a signed wrapper around them);
   * thirty-two leaves room for any mailer's habits while bounding the
   * recursion.
   */
  public static final int MAX_MULTIPART_DEPTH = 32;

  /**
   * How many times over the message's own size the walk will scan multipart
   * content. Jakarta Mail rescans every byte of a multipart to find its
   * boundaries, so each level of nesting costs another pass over everything
   * below it: thirty-two full-size levels turned a 64 MiB message into a
   * quarter of a minute of CPU. Real structures (a signed wrapper around
   * mixed around related around alternative) stay well under this.
   */
  public static final int MAX_MULTIPART_SCAN_FACTOR = 8;

  private EmlParser() {}

  private static Properties lenientProperties() {
    Properties properties = new Properties();
    properties.setProperty("mail.mime.address.strict", "false");
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
    walk(message, "1", 1, options, sink,
        new WalkState((long) MAX_MULTIPART_SCAN_FACTOR * bytes.length));
  }

  /** What the recursive walk carries: the attachment index and its scan budget. */
  private static final class WalkState {
    private final long scanBudget;
    private long scanned;
    private int attachments;

    private WalkState(long scanBudget) {
      this.scanBudget = scanBudget;
    }

    /** Charges a multipart's bytes to the budget, or refuses when they do not fit. */
    private boolean admitScan(long bytes) {
      if (scanned + bytes > scanBudget) {
        return false;
      }
      scanned += bytes;
      return true;
    }
  }

  /**
   * One step of the MIME walk; {@code depth} is how many parts deep this one
   * sits, the root being 1. Only multipart nesting recurses: a nested
   * message/rfc822 is handed over whole as an attachment and never walked.
   */
  private static void walk(
      Part part, String path, int depth, ParseOptions options, ParseSink sink,
      WalkState state) {
    if (isMultipart(part)) {
      // A megabyte of input nests thousands of multiparts deep, and every
      // level rescans the bytes below it before the recursion ends in a
      // StackOverflowError. Past either bound the subtree is kept, whole and
      // unwalked, as the bytes of one attachment.
      String refusal = depth > MAX_MULTIPART_DEPTH
          ? "nests multiparts deeper than " + MAX_MULTIPART_DEPTH + " levels"
          : !state.admitScan(size(part))
              ? "would take the multipart scan past " + MAX_MULTIPART_SCAN_FACTOR
                  + " times the message's size"
              : null;
      if (refusal != null) {
        sink.warn("part " + path + " " + refusal
            + "; its subtree was emitted as one opaque attachment rather than walked");
        emitAttachment(part, path, HeaderProjection.baseType(header(part, "Content-Type", "")),
            filename(part), disposition(part), options, sink, state);
        return;
      }
      MimeMultipart multipart;
      int count;
      try {
        multipart = new LenientMultipart(part);
        count = multipart.getCount();
      } catch (MessagingException broken) {
        throw new InvalidEmailException(
            "unreadable multipart at " + path + ": " + broken.getMessage(), broken);
      }
      if (count == 0) {
        emitPreamble(part, multipart, path, sink);
        return;
      }
      for (int index = 0; index < count; index++) {
        try {
          walk(multipart.getBodyPart(index), path + "." + (index + 1), depth + 1, options, sink,
              state);
        } catch (MessagingException broken) {
          sink.warn("part " + path + "." + (index + 1) + " skipped: " + broken.getMessage());
        }
      }
      return;
    }
    emitLeaf(part, path, options, sink, state);
  }

  /** A part's content size in bytes, or 0 when Jakarta Mail cannot tell. */
  private static long size(Part part) {
    try {
      return Math.max(0, part.getSize());
    } catch (MessagingException unknown) {
      return 0;
    }
  }

  /**
   * Parses multipart bodies with the leniency set per instance rather than
   * read from the {@code mail.mime.multipart.*} System properties, which is
   * where Jakarta Mail would otherwise look each time a multipart parses.
   */
  private static final class LenientMultipart extends MimeMultipart {

    private LenientMultipart(Part part) throws MessagingException {
      super(new MimePartDataSource(mimePart(part)));
    }

    private static MimePart mimePart(Part part) throws MessagingException {
      if (part instanceof MimePart mime) {
        return mime;
      }
      throw new MessagingException("not a MIME part: " + part.getClass().getName());
    }

    @Override
    protected void initializeProperties() {
      // A message cut off mid-part keeps every part before the cut.
      ignoreMissingEndBoundary = true;
      // A multipart that forgot its boundary parameter is read by the
      // first boundary-shaped line, as Jakarta Mail does by default.
      ignoreMissingBoundaryParameter = true;
      ignoreExistingBoundaryParameter = false;
      // No parts is an empty container, not a broken message.
      allowEmpty = true;
    }
  }

  /**
   * A multipart with no parts: a container emptied on the way (an
   * attachment-stripping gateway), or a message that only claims to be
   * multipart and whose whole text sits before a boundary that never comes.
   * Either way the text ahead of the first boundary is all the content
   * there is, so it is kept as a plain body rather than dropped.
   */
  private static void emitPreamble(
      Part part, MimeMultipart multipart, String path, ParseSink sink) {
    String preamble;
    try {
      preamble = multipart.getPreamble();
    } catch (MessagingException unreadable) {
      preamble = null;
    }
    if (preamble == null || preamble.isBlank()) {
      sink.warn("multipart at part " + path + " carries no parts");
      return;
    }
    sink.warn("multipart at part " + path
        + " carries no parts; the text before its first boundary was read as a plain body");
    Decoded decoded = utf8OrLatin1(preamble.getBytes(StandardCharsets.ISO_8859_1));
    sink.bodyPart(
        BodyPart.newBuilder()
            .setPartId(path)
            .setMediaType(BodyMediaType.BODY_MEDIA_TYPE_PLAIN)
            .setContentTypeRaw(HeaderProjection.baseType(header(part, "Content-Type", "")))
            .setText(decoded.text().strip())
            .setCharset(decoded.charset())
            .build());
  }

  /** Bytes with no declared charset: UTF-8 when they are valid UTF-8, else ISO-8859-1. */
  private static Decoded utf8OrLatin1(byte[] bytes) {
    try {
      return new Decoded(
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), "utf-8");
    } catch (CharacterCodingException notUtf8) {
      return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), "iso-8859-1");
    }
  }

  private static void emitLeaf(
      Part part, String path, ParseOptions options, ParseSink sink, WalkState state) {
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
    emitAttachment(part, path, baseType, filename, disposition, options, sink, state);
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
      WalkState state) {
    byte[] payload = payload(part, path, sink);
    int index = state.attachments++;
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
      return recoverText(part, declaredCharset, path, sink);
    }
  }

  /**
   * The text of a body part Jakarta Mail would not decode. Either the
   * transfer encoding is damaged (a base64 body cut short, an encoding
   * nobody knows) or the charset is unknown or lying; the two are told apart
   * here, each is repaired on its own, and the warning names the one that
   * actually failed. An unknown charset falls back to ISO-8859-1, which maps
   * every byte to a code point, so the content survives round-tripping.
   */
  private static Decoded recoverText(
      Part part, String declaredCharset, String path, ParseSink sink) {
    TransferDecoding.Recovered recovered;
    try {
      recovered = TransferDecoding.recover(part);
    } catch (IOException | MessagingException unreadable) {
      throw new InvalidEmailException(
          "unreadable body at part " + path + ": " + unreadable.getMessage(), unreadable);
    }
    if (!recovered.damage().isEmpty()) {
      sink.warn("part " + path + " has a " + recovered.damage()
          + "; the text that decoded was kept");
    }
    Charset charset = charset(declaredCharset);
    if (charset == null) {
      sink.warn("part " + path + " declared charset '" + declaredCharset
          + "' which could not be decoded; read as ISO-8859-1");
      return new Decoded(new String(recovered.bytes(), StandardCharsets.ISO_8859_1), "iso-8859-1");
    }
    return new Decoded(new String(recovered.bytes(), charset), declaredCharset);
  }

  /**
   * The charset a body declared, or US-ASCII when it declared none (RFC
   * 2045's default, and Jakarta Mail's). Null when this JVM cannot decode it.
   */
  private static Charset charset(String declared) {
    try {
      return Charset.forName(declared.isEmpty() ? "us-ascii" : MimeUtility.javaCharset(declared));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  private static byte[] payload(Part part, String path, ParseSink sink) {
    try (InputStream stream = part.getInputStream()) {
      return stream.readAllBytes();
    } catch (IOException | MessagingException refused) {
      // A damaged transfer encoding must not cost the bytes that did decode:
      // a truncated attachment is still mostly the attachment.
      try {
        TransferDecoding.Recovered recovered = TransferDecoding.recover(part);
        sink.warn("attachment payload at part " + path + " has a "
            + (recovered.damage().isEmpty() ? "transfer encoding Jakarta Mail refused ("
                + refused.getMessage() + ")" : recovered.damage())
            + "; kept the " + recovered.bytes().length + " bytes that decoded");
        return recovered.bytes();
      } catch (IOException | MessagingException unreadable) {
        sink.warn("attachment payload at part " + path + " is unreadable: "
            + unreadable.getMessage());
        return new byte[0];
      }
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
    String value;
    try {
      value = part.getFileName();
    } catch (MessagingException strictGrammarRefused) {
      value = null;
    }
    if (value == null || value.isBlank()) {
      // Jakarta Mail parses parameters strictly, so an unquoted filename
      // with a space in it fails the whole header and the name is lost.
      value = lenientParameter(header(part, "Content-Disposition", ""), "filename");
      if (value.isEmpty()) {
        value = lenientParameter(header(part, "Content-Type", ""), "name");
      }
    }
    return HeaderProjection.decoded(value).trim();
  }

  /**
   * One parameter of a structured header, read the way Jakarta Mail reads
   * it under {@code mail.mime.parameters.strict=false}: the value runs to
   * the next semicolon outside quotes, so {@code filename=my report.pdf}
   * survives. Used only after the strict grammar has refused a header.
   *
   * @return the unquoted value, or empty when the parameter is absent
   */
  private static String lenientParameter(String header, String name) {
    String unfolded = MimeUtility.unfold(header);
    int start = 0;
    boolean first = true;
    while (start <= unfolded.length()) {
      int end = start;
      boolean quoted = false;
      while (end < unfolded.length()) {
        char character = unfolded.charAt(end);
        if (character == '\\' && quoted && end + 1 < unfolded.length()) {
          end += 2;
          continue;
        }
        if (character == '"') {
          quoted = !quoted;
        } else if (character == ';' && !quoted) {
          break;
        }
        end++;
      }
      String segment = unfolded.substring(start, end);
      int equals = segment.indexOf('=');
      // The first segment is the type or disposition itself, never a parameter.
      if (!first && equals > 0 && segment.substring(0, equals).trim().equalsIgnoreCase(name)) {
        return unquote(segment.substring(equals + 1).trim());
      }
      first = false;
      start = end + 1;
    }
    return "";
  }

  private static String unquote(String value) {
    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      return value;
    }
    StringBuilder unquoted = new StringBuilder(value.length());
    for (int index = 1; index < value.length() - 1; index++) {
      char character = value.charAt(index);
      if (character == '\\' && index + 1 < value.length() - 1) {
        character = value.charAt(++index);
      }
      unquoted.append(character);
    }
    return unquoted.toString();
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
      return lenientParameter(contentType, name);
    }
  }
}
