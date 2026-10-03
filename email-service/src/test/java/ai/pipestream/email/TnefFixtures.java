package ai.pipestream.email;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Builds TNEF streams (winmail.dat) in memory, from the MS-OXTNEF layout up:
 * a signature, then length-framed records, some of which carry MAPI
 * property lists. Apache POI can read TNEF but not write it, and no
 * customer message may enter this tree, so every byte is placed here.
 */
final class TnefFixtures {

  /** TNEF attribute ids (MS-OXTNEF 2.1.3.3) and their attribute types. */
  private static final int ATT_BODY = 0x800C;
  private static final int ATT_ATTACH_DATA = 0x800F;
  private static final int ATT_ATTACH_TITLE = 0x8010;
  private static final int ATT_ATTACH_RENDER_DATA = 0x9002;
  private static final int ATT_MAPI_PROPS = 0x9003;
  private static final int ATT_ATTACHMENT = 0x9005;
  private static final int ATT_OEM_CODEPAGE = 0x9007;
  private static final int ATP_STRING = 0x0001;
  private static final int ATP_TEXT = 0x0002;
  private static final int ATP_LONG = 0x0005;
  private static final int ATP_BYTE = 0x0006;

  /** MAPI property ids and types, as in MsgFixtures. */
  private static final int PID_RTF_COMPRESSED = 0x1009;
  private static final int PID_ATTACH_LONG_FILENAME = 0x3707;
  private static final int PID_ATTACH_MIME_TAG = 0x370E;
  private static final int PID_ATTACH_CONTENT_ID = 0x3712;
  private static final int TYPE_STRING8 = 0x001E;
  private static final int TYPE_UNICODE = 0x001F;
  private static final int TYPE_BINARY = 0x0102;

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  static final String PLAIN_BODY = "Die Anhörung wurde auf Freitag verschoben.";
  static final String RTF_BODY_TEXT = "Hearing moved to Friday.";
  static final String ORDER_SHORT_NAME = "SCHEDU~1.PDF";
  static final String ORDER_LONG_NAME = "scheduling order.pdf";
  static final byte[] ORDER_BYTES =
      "%PDF-1.4 the scheduling order, carried inside winmail.dat".getBytes(StandardCharsets.US_ASCII);
  static final String SEAL_NAME = "seal.png";
  static final byte[] SEAL_BYTES = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
  static final String SEAL_CONTENT_ID = "seal@example.gov";

  private TnefFixtures() {}

  /**
   * The winmail.dat a rich-text Outlook message carries: an RTF body
   * (optionally with the 8-bit plain rendering beside it) and two
   * attachments, one named by a long filename and a MIME tag, one inline
   * with a content id.
   */
  static byte[] winmailDat(boolean withPlainBody) {
    Stream stream = new Stream()
        .message(ATT_OEM_CODEPAGE, ATP_LONG, codePage(1252));
    if (withPlainBody) {
      stream.message(ATT_BODY, ATP_TEXT, (PLAIN_BODY + "\0").getBytes(WINDOWS_1252));
    }
    stream.message(ATT_MAPI_PROPS, ATP_BYTE, new Properties()
        .binary(PID_RTF_COMPRESSED,
            uncompressedRtf("{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}\\f0 " + RTF_BODY_TEXT
                + "\\par}"))
        .build());

    stream.attachment(ATT_ATTACH_RENDER_DATA, ATP_BYTE, renderData())
        .attachment(ATT_ATTACH_TITLE, ATP_STRING, (ORDER_SHORT_NAME + "\0").getBytes(WINDOWS_1252))
        .attachment(ATT_ATTACH_DATA, ATP_BYTE, ORDER_BYTES)
        .attachment(ATT_ATTACHMENT, ATP_BYTE, new Properties()
            .string8(PID_ATTACH_LONG_FILENAME, ORDER_LONG_NAME)
            .string8(PID_ATTACH_MIME_TAG, "application/pdf")
            .build());

    stream.attachment(ATT_ATTACH_RENDER_DATA, ATP_BYTE, renderData())
        .attachment(ATT_ATTACH_TITLE, ATP_STRING, (SEAL_NAME + "\0").getBytes(WINDOWS_1252))
        .attachment(ATT_ATTACH_DATA, ATP_BYTE, SEAL_BYTES)
        .attachment(ATT_ATTACHMENT, ATP_BYTE, new Properties()
            .unicode(PID_ATTACH_CONTENT_ID, SEAL_CONTENT_ID)
            .build());
    return stream.build();
  }

  /**
   * A container of nothing but attachment slots: {@code slots} bare
   * attAttachRenderData records, twenty-five bytes each, no name, no data.
   */
  static byte[] emptySlots(int slots) {
    Stream stream = new Stream().message(ATT_OEM_CODEPAGE, ATP_LONG, codePage(1252));
    for (int slot = 0; slot < slots; slot++) {
      stream.attachment(ATT_ATTACH_RENDER_DATA, ATP_BYTE, renderData());
    }
    return stream.build();
  }

  /**
   * One named attachment followed by {@code records} attAttachment records
   * of a single byte each, twelve bytes apiece, none of them a MAPI property
   * list POI can read.
   */
  static byte[] unreadableRecords(int records) {
    Stream stream = new Stream()
        .message(ATT_OEM_CODEPAGE, ATP_LONG, codePage(1252))
        .attachment(ATT_ATTACH_RENDER_DATA, ATP_BYTE, renderData())
        .attachment(ATT_ATTACH_TITLE, ATP_STRING, (SEAL_NAME + "\0").getBytes(WINDOWS_1252))
        .attachment(ATT_ATTACH_DATA, ATP_BYTE, SEAL_BYTES);
    for (int record = 0; record < records; record++) {
      stream.attachment(ATT_ATTACHMENT, ATP_BYTE, new byte[] {1});
    }
    return stream.build();
  }

  /** {@code slots} attachments that carry one byte of data each and no name. */
  static byte[] namelessAttachments(int slots) {
    Stream stream = new Stream().message(ATT_OEM_CODEPAGE, ATP_LONG, codePage(1252));
    for (int slot = 0; slot < slots; slot++) {
      stream.attachment(ATT_ATTACH_RENDER_DATA, ATP_BYTE, renderData())
          .attachment(ATT_ATTACH_DATA, ATP_BYTE, new byte[] {(byte) slot});
    }
    return stream.build();
  }

  /** Bytes that carry the TNEF signature and nothing readable after it. */
  static byte[] signatureThenGarbage() {
    return new byte[] {0x78, (byte) 0x9F, 0x3E, 0x22, 0x01, 0x00, 0x07, 0x07, 0x07, 0x07};
  }

  // --- MS-OXTNEF plumbing -------------------------------------------------

  /** One TNEF stream: signature, legacy key, then records. */
  private static final class Stream {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private Stream() {
      out.writeBytes(new byte[] {0x78, (byte) 0x9F, 0x3E, 0x22});
      out.writeBytes(uint16(0x0101));
    }

    private Stream message(int id, int type, byte[] data) {
      return record(0x01, id, type, data);
    }

    private Stream attachment(int id, int type, byte[] data) {
      return record(0x02, id, type, data);
    }

    /** Level, attribute id (low word) and type (high word), length, data, checksum. */
    private Stream record(int level, int id, int type, byte[] data) {
      out.write(level);
      out.writeBytes(uint16(id));
      out.writeBytes(uint16(type));
      out.writeBytes(uint32(data.length));
      out.writeBytes(data);
      int checksum = 0;
      for (byte b : data) {
        checksum = (checksum + (b & 0xFF)) & 0xFFFF;
      }
      out.writeBytes(uint16(checksum));
      return this;
    }

    private byte[] build() {
      return out.toByteArray();
    }
  }

  /** A MAPI property list as TNEF encodes one (MS-OXTNEF 2.1.3.4). */
  private static final class Properties {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int count;

    private Properties string8(int id, String value) {
      return variable(TYPE_STRING8, id, (value + "\0").getBytes(WINDOWS_1252));
    }

    private Properties unicode(int id, String value) {
      return variable(TYPE_UNICODE, id, (value + "\0").getBytes(StandardCharsets.UTF_16LE));
    }

    private Properties binary(int id, byte[] value) {
      return variable(TYPE_BINARY, id, value);
    }

    /** Type, id, one value: its length, its bytes, padding to four bytes. */
    private Properties variable(int type, int id, byte[] value) {
      out.writeBytes(uint16(type));
      out.writeBytes(uint16(id));
      out.writeBytes(uint32(1));
      out.writeBytes(uint32(value.length));
      out.writeBytes(value);
      out.writeBytes(new byte[(4 - value.length % 4) % 4]);
      count++;
      return this;
    }

    private byte[] build() {
      ByteArrayOutputStream list = new ByteArrayOutputStream();
      list.writeBytes(uint32(count));
      list.writeBytes(out.toByteArray());
      return list.toByteArray();
    }
  }

  /** attAttachRenderData: a plain file attachment, position and size irrelevant. */
  private static byte[] renderData() {
    ByteBuffer buffer = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putShort((short) 1);
    buffer.putInt(-1);
    buffer.putShort((short) -1);
    buffer.putShort((short) -1);
    buffer.putInt(0);
    return buffer.array();
  }

  /** attOemCodepage: the primary code page, then a secondary one. */
  private static byte[] codePage(int primary) {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(primary);
    buffer.putInt(0);
    return buffer.array();
  }

  /** PidTagRtfCompressed in its uncompressed ("MELA") form, as in MsgFixtures. */
  private static byte[] uncompressedRtf(String rtf) {
    byte[] raw = rtf.getBytes(StandardCharsets.US_ASCII);
    ByteBuffer buffer = ByteBuffer.allocate(16 + raw.length).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(raw.length + 12);
    buffer.putInt(raw.length);
    buffer.put(new byte[] {'M', 'E', 'L', 'A'});
    buffer.putInt(0);
    buffer.put(raw);
    return buffer.array();
  }

  private static byte[] uint16(int value) {
    return new byte[] {(byte) value, (byte) (value >> 8)};
  }

  private static byte[] uint32(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }
}
