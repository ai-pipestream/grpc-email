package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;
import org.apache.poi.hmef.attribute.MAPIAttribute;
import org.apache.poi.hmef.attribute.MAPIStringAttribute;
import org.apache.poi.hmef.attribute.TNEFAttribute;
import org.apache.poi.hmef.attribute.TNEFMAPIAttribute;
import org.apache.poi.hmef.attribute.TNEFProperty;
import org.apache.poi.hsmf.datatypes.MAPIProperty;

/**
 * Unpacks TNEF, the {@code winmail.dat} / {@code application/ms-tnef}
 * container Outlook and Exchange use to carry a rich-text message's body
 * and attachments through internet mail. Left opaque, everything inside it
 * is invisible: the message seems to carry one meaningless winmail.dat.
 *
 * <p>The stream is framed here, record by record (MS-OXTNEF 2.1.3), and only
 * the MAPI property lists inside it are decoded by Apache POI's HMEF. POI's
 * own reader is all or nothing: one record it cannot read (a compressed RTF
 * body over its 1 MB property cap, a stream cut short along with the base64
 * around it) throws away every attachment. Framed here, a damaged record
 * costs only itself, and a stream cut short keeps everything before the cut.
 * Attachment data is sliced straight out of the stream, so no per-record cap
 * applies beyond the message's own.
 *
 * <p>Nothing is recursed into: an attachment inside the container that is
 * itself TNEF, or an embedded message, is described rather than unpacked.
 */
final class TnefContainer {

  /** TNEF signature 0x223E9F78, little-endian, the first four bytes of every stream. */
  private static final byte[] SIGNATURE = {0x78, (byte) 0x9F, 0x3E, 0x22};

  /** Signature plus the two-byte legacy key that follows it. */
  private static final int HEADER_LENGTH = 6;

  /** Level byte, attribute id and type, length: what precedes each record's data. */
  private static final int RECORD_HEADER_LENGTH = 9;

  /** The checksum that follows each record's data. */
  private static final int CHECKSUM_LENGTH = 2;

  private static final int LEVEL_MESSAGE = 1;
  private static final int LEVEL_ATTACHMENT = 2;

  /** attOemCodepage: the code page of the stream's 8-bit strings. */
  private static final int ID_OEM_CODEPAGE = 0x9007;

  /**
   * Attachment slots read from one container. A record can be eleven bytes,
   * so without a bound a hostile container opens millions of slots, each an
   * event and a warning; real ones carry a handful.
   */
  static final int MAX_ATTACHMENTS = 10_000;

  private TnefContainer() {}

  /** True when a payload starts with the TNEF signature; labels are never trusted. */
  static boolean isTnef(byte[] payload) {
    if (payload.length < HEADER_LENGTH) {
      return false;
    }
    for (int index = 0; index < SIGNATURE.length; index++) {
      if (payload[index] != SIGNATURE[index]) {
        return false;
      }
    }
    return true;
  }

  /** How an unpack went, for the parser that found the container. */
  record Unpacked(boolean readable, boolean emittedBody) {}

  /**
   * Streams what the container holds: its body, when the message around it
   * has none of its own (a rich-text message usually carries a plain-text
   * rendering of the same body beside its winmail.dat), then its
   * attachments, named under the container's own part path.
   *
   * @param tnef the container's bytes, signature already checked
   * @param path the part id of the container itself, for example "1.2"
   * @param wantBody whether the message has emitted no body so far
   * @param nextIndex hands out the parser's running attachment index
   * @return {@code readable} false when nothing at all could be read, in
   *     which case nothing was emitted and the caller should describe the
   *     container itself, opaque
   */
  static Unpacked unpack(
      byte[] tnef, String path, boolean wantBody, ParseOptions options, ParseSink sink,
      IntSupplier nextIndex) {
    Contents contents = read(tnef);
    for (String problem : contents.problems) {
      sink.warn("TNEF container at part " + path + ": " + problem);
    }
    if (!contents.recognized) {
      return new Unpacked(false, false);
    }
    boolean emittedBody = wantBody && emitBody(contents, path, sink);
    int empty = 0;
    for (int slot = 0; slot < contents.attachments.size(); slot++) {
      sink.checkpoint();
      Packed packed = contents.attachments.get(slot);
      if (packed.data == null && packed.filename.isEmpty()) {
        empty++;
        continue;
      }
      emitAttachment(packed, path + "/attach:" + slot, options, sink, nextIndex.getAsInt());
    }
    if (empty > 0) {
      sink.warn("TNEF container at part " + path + ": " + empty
          + " attachment slot(s) with neither a name nor data were skipped");
    }
    return new Unpacked(true, emittedBody);
  }

  /**
   * The body, by the same rule as a .msg: plain text and HTML when the
   * container has them, RTF reduced to plain text only when it has neither.
   */
  private static boolean emitBody(Contents contents, String path, ParseSink sink) {
    boolean emitted = false;
    if (!contents.plain.isBlank()) {
      sink.bodyPart(body(path + "/body:plain", BodyMediaType.BODY_MEDIA_TYPE_PLAIN, "text/plain",
          contents.plain, "PidTagBody"));
      emitted = true;
    }
    if (!contents.html.isBlank()) {
      sink.bodyPart(body(path + "/body:html", BodyMediaType.BODY_MEDIA_TYPE_HTML, "text/html",
          contents.html, "PidTagHtml"));
      emitted = true;
    }
    if (emitted || contents.rtf.isEmpty()) {
      return emitted;
    }
    String extracted;
    try {
      extracted = RtfPlainText.extract(contents.rtf);
    } catch (RuntimeException malformed) {
      sink.warn("TNEF container at part " + path
          + ": its RTF body could not be converted to text: " + malformed);
      return false;
    }
    sink.warn("TNEF container at part " + path
        + ": RTF-only body: plain text extracted without layout, tables, or formatting");
    if (extracted.isEmpty()) {
      return false;
    }
    sink.bodyPart(body(path + "/body:rtf", BodyMediaType.BODY_MEDIA_TYPE_PLAIN, "text/plain",
        extracted, "PidTagRtfCompressed"));
    return true;
  }

  private static BodyPart body(
      String partId, BodyMediaType mediaType, String contentType, String text, String source) {
    return BodyPart.newBuilder()
        .setPartId(partId)
        .setMediaType(mediaType)
        .setContentTypeRaw(contentType)
        .setText(text)
        .setSourceProperty(source)
        .build();
  }

  private static void emitAttachment(
      Packed packed, String partId, ParseOptions options, ParseSink sink, int index) {
    byte[] payload = packed.data == null ? new byte[0] : packed.data;
    if (packed.data == null) {
      sink.warn("attachment " + index + " at part " + partId + " carries no data in the TNEF"
          + " container (an embedded message or an OLE object); described without its bytes");
    }
    if (packed.filename.isEmpty()) {
      sink.warn("attachment " + index + " at part " + partId + " has no filename");
    }
    String contentId = HeaderProjection.stripAngles(packed.contentId);
    Attachment.Builder attachment = Attachment.newBuilder()
        .setIndex(index)
        .setPartId(partId)
        .setFilename(packed.filename)
        .setContentType(HeaderProjection.baseType(packed.mimeTag))
        .setSizeBytes(payload.length)
        .setContentId(contentId)
        .setInline(!contentId.isEmpty());
    options.attachPayload(attachment, payload, sink);
    sink.attachment(attachment.build());
  }

  // --- reading ------------------------------------------------------------

  /** One attachment as the container stores it. */
  private static final class Packed {
    private String title = "";
    private String longFilename = "";
    private String shortFilename = "";
    private String filename = "";
    private String mimeTag = "";
    private String contentId = "";
    private byte[] data;
  }

  /** Everything read from one stream, and what could not be. */
  private static final class Contents {
    private boolean recognized;
    /** Until attOemCodepage says otherwise, Outlook's Western default. */
    private Charset codePage = defaultCodePage();
    private String attBody = "";
    private String plain = "";
    private String html = "";
    private String rtf = "";
    private int internetCodePage;
    private byte[] htmlBytes;
    private final List<Packed> attachments = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    /**
     * MAPI property records POI could not read. A record can be twelve
     * bytes, so these are counted and reported once rather than one problem
     * each: a container of nothing else would otherwise turn every dozen
     * bytes of input into a warning on the trailer.
     */
    private int unreadableRecords;
    /** What went wrong with the first of them, as an example. */
    private String firstUnreadable = "";
  }

  private static Contents read(byte[] tnef) {
    Contents contents = new Contents();
    int position = HEADER_LENGTH;
    while (position < tnef.length) {
      int level = tnef[position] & 0xFF;
      if (level == '\r' || level == '\n') {
        // Line breaks some writers leave between records, as POI tolerates.
        position++;
        continue;
      }
      if (level == 0 && onlyZeros(tnef, position)) {
        // Zero padding after the last record.
        break;
      }
      if (level != LEVEL_MESSAGE && level != LEVEL_ATTACHMENT) {
        contents.problems.add("unknown record level " + level + " at offset " + position
            + "; the rest of the container was not read");
        break;
      }
      if (position + RECORD_HEADER_LENGTH > tnef.length) {
        contents.problems.add("the container ends inside a record header at offset " + position
            + "; it was cut short");
        break;
      }
      int id = u16(tnef, position + 1);
      long length = u32(tnef, position + 5);
      long end = position + RECORD_HEADER_LENGTH + length + CHECKSUM_LENGTH;
      if (end > tnef.length) {
        contents.problems.add("record 0x" + Integer.toHexString(id) + " at offset " + position
            + " claims " + length + " bytes, more than remain; the container was cut short and"
            + " everything before the cut was kept");
        break;
      }
      contents.recognized = true;
      int dataStart = position + RECORD_HEADER_LENGTH;
      if (!record(contents, level, id, tnef, position + 1, dataStart, (int) length)) {
        contents.problems.add("the container opens more than " + MAX_ATTACHMENTS
            + " attachment slots; the rest were not read");
        break;
      }
      position = (int) end;
    }
    finish(contents);
    return contents;
  }

  /**
   * Files one record under the message or its current attachment.
   *
   * @return false when the record would open one attachment slot too many
   */
  private static boolean record(
      Contents contents, int level, int id, byte[] tnef, int from, int dataStart, int length) {
    if (level == LEVEL_ATTACHMENT) {
      if (contents.attachments.isEmpty() || id == TNEFProperty.ID_ATTACHRENDERDATA.id) {
        // attAttachRenderData opens every attachment (MS-OXTNEF 2.1.3.3).
        if (contents.attachments.size() == MAX_ATTACHMENTS) {
          return false;
        }
        contents.attachments.add(new Packed());
      }
      Packed current = contents.attachments.getLast();
      if (id == TNEFProperty.ID_ATTACHDATA.id) {
        current.data = Arrays.copyOfRange(tnef, dataStart, dataStart + length);
      } else if (id == TNEFProperty.ID_ATTACHTITLE.id) {
        current.title = string(contents, tnef, dataStart, length);
      } else if (id == TNEFProperty.ID_ATTACHMENT.id) {
        for (MAPIAttribute property : properties(contents, id, tnef, from, dataStart, length)) {
          int tag = property.getProperty().id;
          if (tag == MAPIProperty.ATTACH_LONG_FILENAME.id) {
            current.longFilename = text(property);
          } else if (tag == MAPIProperty.ATTACH_FILENAME.id) {
            current.shortFilename = text(property);
          } else if (tag == MAPIProperty.ATTACH_MIME_TAG.id) {
            current.mimeTag = text(property);
          } else if (tag == MAPIProperty.ATTACH_CONTENT_ID.id) {
            current.contentId = text(property);
          }
        }
      }
      return true;
    }
    if (id == ID_OEM_CODEPAGE && length >= 4) {
      Charset codePage = codePage((int) u32(tnef, dataStart));
      if (codePage != null) {
        contents.codePage = codePage;
      }
    } else if (id == TNEFProperty.ID_BODY.id) {
      // attBody is 8-bit text in the stream's code page (MS-OXTNEF
      // 2.1.3.3.3); it is decoded once that code page is known.
      contents.attBody = new String(tnef, dataStart, length, StandardCharsets.ISO_8859_1);
    } else if (id == TNEFProperty.ID_MAPIPROPERTIES.id) {
      for (MAPIAttribute property : properties(contents, id, tnef, from, dataStart, length)) {
        int tag = property.getProperty().id;
        if (tag == MAPIProperty.BODY.id) {
          contents.plain = text(property);
        } else if (tag == MAPIProperty.BODY_HTML.id) {
          if (property instanceof MAPIStringAttribute) {
            contents.html = text(property);
          } else {
            contents.htmlBytes = property.getData();
          }
        } else if (tag == MAPIProperty.RTF_COMPRESSED.id) {
          contents.rtf = text(property);
        } else if (tag == MAPIProperty.INTERNET_CPID.id && property.getData().length >= 4) {
          contents.internetCodePage = (int) u32(property.getData(), 0);
        }
      }
    }
    return true;
  }

  /** Decodes what needed the whole stream first: code pages, and names to choose between. */
  private static void finish(Contents contents) {
    if (contents.unreadableRecords > 0) {
      contents.problems.add(contents.unreadableRecords
          + " MAPI property record(s) could not be read and were skipped, the first being "
          + contents.firstUnreadable);
    }
    if (contents.plain.isEmpty() && !contents.attBody.isEmpty()) {
      contents.plain = stripNul(new String(
          contents.attBody.getBytes(StandardCharsets.ISO_8859_1), contents.codePage));
    }
    if (contents.html.isEmpty() && contents.htmlBytes != null) {
      Charset charset = codePage(contents.internetCodePage);
      contents.html = stripNul(new String(contents.htmlBytes,
          charset == null ? StandardCharsets.UTF_8 : charset));
    }
    for (Packed packed : contents.attachments) {
      packed.filename = firstNonEmpty(packed.longFilename, packed.title, packed.shortFilename);
    }
  }

  /** A MAPI property list, decoded by POI; an unreadable list is counted and skipped. */
  private static List<MAPIAttribute> properties(
      Contents contents, int id, byte[] tnef, int from, int dataStart, int length) {
    try {
      TNEFAttribute attribute = TNEFAttribute.create(
          new ByteArrayInputStream(tnef, from, dataStart - from + length + CHECKSUM_LENGTH));
      if (attribute instanceof TNEFMAPIAttribute mapi) {
        return mapi.getMAPIAttributes();
      }
    } catch (IOException | RuntimeException unreadable) {
      if (contents.unreadableRecords++ == 0) {
        contents.firstUnreadable = "record 0x" + Integer.toHexString(id) + " ("
            + unreadable.getMessage() + ")";
      }
    }
    return List.of();
  }

  private static String text(MAPIAttribute property) {
    String value = MAPIStringAttribute.getAsString(property);
    return value == null ? "" : stripNul(value).trim();
  }

  private static String string(Contents contents, byte[] tnef, int from, int length) {
    return stripNul(new String(tnef, from, length, contents.codePage)).trim();
  }

  private static String stripNul(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '\0') {
      end--;
    }
    return value.substring(0, end);
  }

  /** A Windows code page number as a charset, or null when this JVM has none for it. */
  private static Charset codePage(int number) {
    if (number <= 0) {
      return null;
    }
    switch (number) {
      case 65001:
        return StandardCharsets.UTF_8;
      case 1200:
        return StandardCharsets.UTF_16LE;
      case 1201:
        return StandardCharsets.UTF_16BE;
      case 20127:
        return StandardCharsets.US_ASCII;
      case 28591:
        return StandardCharsets.ISO_8859_1;
      default:
        break;
    }
    for (String name : new String[] {"windows-" + number, "cp" + number, "x-windows-" + number}) {
      try {
        return Charset.forName(name);
      } catch (IllegalArgumentException unknown) {
        // try the next spelling
      }
    }
    return null;
  }

  private static Charset defaultCodePage() {
    Charset windows1252 = codePage(1252);
    return windows1252 == null ? StandardCharsets.ISO_8859_1 : windows1252;
  }

  private static boolean onlyZeros(byte[] bytes, int from) {
    for (int index = from; index < bytes.length; index++) {
      if (bytes[index] != 0) {
        return false;
      }
    }
    return true;
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (!value.isEmpty()) {
        return value;
      }
    }
    return "";
  }

  private static int u16(byte[] bytes, int at) {
    return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
  }

  private static long u32(byte[] bytes, int at) {
    return (bytes[at] & 0xFFL) | (bytes[at + 1] & 0xFFL) << 8 | (bytes[at + 2] & 0xFFL) << 16
        | (bytes[at + 3] & 0xFFL) << 24;
  }
}
