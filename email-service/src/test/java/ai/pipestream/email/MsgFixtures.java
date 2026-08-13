package ai.pipestream.email;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Builds Outlook .msg bytes in memory, from the MS-OXMSG layout up: an OLE2
 * compound file whose streams are MAPI property chunks.
 *
 * <p>Apache POI can read .msg but cannot write one, and no customer message
 * may enter this tree, so the fixtures are assembled here byte by byte.
 * Every assertion in the suite is then against a value this file placed.
 */
final class MsgFixtures {

  /** Milliseconds between the FILETIME epoch (1601-01-01) and 1970-01-01. */
  private static final long FILETIME_EPOCH_OFFSET_MILLIS = 11_644_473_600_000L;

  /** MAPI property ids used by the fixtures, from MS-OXPROPS. */
  private static final int PID_TRANSPORT_MESSAGE_HEADERS = 0x007D;
  private static final int PID_CLIENT_SUBMIT_TIME = 0x0039;
  private static final int PID_MESSAGE_DELIVERY_TIME = 0x0E06;
  private static final int PID_SUBJECT = 0x0037;
  private static final int PID_DISPLAY_TO = 0x0E04;
  private static final int PID_SENDER_NAME = 0x0C1A;
  private static final int PID_SENDER_EMAIL_ADDRESS = 0x0C1F;
  private static final int PID_BODY = 0x1000;
  private static final int PID_RTF_COMPRESSED = 0x1009;
  private static final int PID_BODY_HTML = 0x1013;
  private static final int PID_INTERNET_MESSAGE_ID = 0x1035;
  private static final int PID_RECIPIENT_TYPE = 0x0C15;
  private static final int PID_DISPLAY_NAME = 0x3001;
  private static final int PID_EMAIL_ADDRESS = 0x3003;
  private static final int PID_ATTACH_DATA = 0x3701;
  private static final int PID_ATTACH_FILENAME = 0x3704;
  private static final int PID_ATTACH_LONG_FILENAME = 0x3707;
  private static final int PID_ATTACH_MIME_TAG = 0x370E;
  private static final int PID_ATTACH_CONTENT_ID = 0x3712;

  /** MAPI property types. */
  private static final int TYPE_LONG = 0x0003;
  private static final int TYPE_TIME = 0x0040;
  private static final int TYPE_BINARY = 0x0102;
  private static final int TYPE_UNICODE = 0x001F;

  /** PidTagRecipientType values. */
  static final int RECIPIENT_TO = 1;
  static final int RECIPIENT_CC = 2;
  static final int RECIPIENT_BCC = 3;

  static final String SUBJECT = "Docket 24-1183 scheduling order";
  static final String SENDER_NAME = "Clerk of Court";
  static final String SENDER_EMAIL = "clerk@example.gov";
  static final String TO_NAME = "Ada Counsel";
  static final String TO_EMAIL = "ada@example.com";
  static final String CC_NAME = "Bob Paralegal";
  static final String CC_EMAIL = "bob@example.com";
  static final String BCC_NAME = "Records Room";
  static final String BCC_EMAIL = "records@example.gov";
  static final String MESSAGE_ID = "msg-0001@example.gov";
  static final String PLAIN_BODY = "The hearing is set for the 14th.";
  static final String HTML_BODY = "<html><body><p>The hearing is set for the 14th.</p></body></html>";
  static final String ATTACHMENT_NAME = "order.pdf";
  static final byte[] ATTACHMENT_BYTES =
      "%PDF-1.4 not really a pdf".getBytes(StandardCharsets.US_ASCII);
  static final String INLINE_CONTENT_ID = "seal@example.gov";
  static final long SUBMIT_TIME_MILLIS = 1_700_000_000_000L;
  static final long DELIVERY_TIME_MILLIS = 1_700_000_060_000L;

  private MsgFixtures() {}

  /**
   * A message with both bodies, three role-tagged recipients, transport
   * headers, and two attachments (one of them inline with a content id).
   */
  static byte[] full() throws IOException {
    try (POIFSFileSystem container = new POIFSFileSystem();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      DirectoryEntry root = container.getRoot();
      unicode(root, PID_SUBJECT, SUBJECT);
      unicode(root, PID_SENDER_NAME, SENDER_NAME);
      unicode(root, PID_SENDER_EMAIL_ADDRESS, SENDER_EMAIL);
      unicode(root, PID_DISPLAY_TO, TO_NAME);
      unicode(root, PID_INTERNET_MESSAGE_ID, "<" + MESSAGE_ID + ">");
      unicode(root, PID_BODY, PLAIN_BODY);
      binary(root, PID_BODY_HTML, HTML_BODY.getBytes(StandardCharsets.US_ASCII));
      unicode(root, PID_TRANSPORT_MESSAGE_HEADERS,
          "Received: from mail.example.gov by mx.example.com; "
              + "Tue, 14 Nov 2023 22:13:20 +0000\r\n"
              + "In-Reply-To: <parent-0000@example.com>\r\n"
              + "References: <root-0000@example.com> <parent-0000@example.com>\r\n"
              + "Content-Type: text/plain; charset=utf-8\r\n"
              + "X-Court-Docket: 24-1183\r\n");

      recipient(root, 0, TO_NAME, TO_EMAIL, RECIPIENT_TO);
      recipient(root, 1, CC_NAME, CC_EMAIL, RECIPIENT_CC);
      recipient(root, 2, BCC_NAME, BCC_EMAIL, RECIPIENT_BCC);

      attachment(root, 0, ATTACHMENT_NAME, "application/pdf", ATTACHMENT_BYTES, "");
      attachment(root, 1, "seal.png", "image/png",
          new byte[] {(byte) 0x89, 'P', 'N', 'G'}, INLINE_CONTENT_ID);

      List<byte[]> properties = new ArrayList<>();
      properties.add(timeEntry(PID_CLIENT_SUBMIT_TIME, SUBMIT_TIME_MILLIS));
      properties.add(timeEntry(PID_MESSAGE_DELIVERY_TIME, DELIVERY_TIME_MILLIS));
      root.createDocument(PROPERTIES_STREAM,
          new ByteArrayInputStream(messageProperties(3, 2, properties)));

      container.writeFilesystem(out);
      return out.toByteArray();
    }
  }

  /** A message whose only body is RTF, exercising the degraded extraction path. */
  static byte[] rtfOnly(String rtf) throws IOException {
    try (POIFSFileSystem container = new POIFSFileSystem();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      DirectoryEntry root = container.getRoot();
      unicode(root, PID_SUBJECT, "Rich text only");
      unicode(root, PID_SENDER_NAME, SENDER_NAME);
      unicode(root, PID_SENDER_EMAIL_ADDRESS, SENDER_EMAIL);
      binary(root, PID_RTF_COMPRESSED, uncompressedRtfStream(rtf));
      recipient(root, 0, TO_NAME, TO_EMAIL, RECIPIENT_TO);
      root.createDocument(PROPERTIES_STREAM,
          new ByteArrayInputStream(messageProperties(1, 0, List.of())));
      container.writeFilesystem(out);
      return out.toByteArray();
    }
  }

  /** An OLE2 compound file that is not a MAPI message at all. */
  static byte[] ole2ButNotMapi() throws IOException {
    try (POIFSFileSystem container = new POIFSFileSystem();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      container.getRoot().createDocument("WordDocument",
          new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
      container.writeFilesystem(out);
      return out.toByteArray();
    }
  }

  // --- MS-OXMSG plumbing --------------------------------------------------

  private static final String PROPERTIES_STREAM = "__properties_version1.0";

  private static void unicode(DirectoryEntry parent, int propertyId, String value)
      throws IOException {
    parent.createDocument(chunkName(propertyId, TYPE_UNICODE),
        new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
  }

  private static void binary(DirectoryEntry parent, int propertyId, byte[] value)
      throws IOException {
    parent.createDocument(chunkName(propertyId, TYPE_BINARY), new ByteArrayInputStream(value));
  }

  private static String chunkName(int propertyId, int type) {
    return String.format("__substg1.0_%04X%04X", propertyId, type);
  }

  private static void recipient(
      DirectoryEntry root, int index, String name, String email, int recipientType)
      throws IOException {
    DirectoryEntry storage =
        root.createDirectory(String.format("__recip_version1.0_#%08X", index));
    unicode(storage, PID_DISPLAY_NAME, name);
    unicode(storage, PID_EMAIL_ADDRESS, email);
    storage.createDocument(PROPERTIES_STREAM, new ByteArrayInputStream(
        storageProperties(List.of(longEntry(PID_RECIPIENT_TYPE, recipientType)))));
  }

  private static void attachment(
      DirectoryEntry root, int index, String filename, String mimeTag, byte[] payload,
      String contentId) throws IOException {
    DirectoryEntry storage =
        root.createDirectory(String.format("__attach_version1.0_#%08X", index));
    unicode(storage, PID_ATTACH_FILENAME, filename);
    unicode(storage, PID_ATTACH_LONG_FILENAME, filename);
    unicode(storage, PID_ATTACH_MIME_TAG, mimeTag);
    binary(storage, PID_ATTACH_DATA, payload);
    if (!contentId.isEmpty()) {
      unicode(storage, PID_ATTACH_CONTENT_ID, contentId);
    }
    storage.createDocument(PROPERTIES_STREAM,
        new ByteArrayInputStream(storageProperties(List.of())));
  }

  /** Top-level property stream: 32-byte header, then fixed-length entries. */
  private static byte[] messageProperties(
      int recipientCount, int attachmentCount, List<byte[]> entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[8]);
    out.writeBytes(uint32(recipientCount));
    out.writeBytes(uint32(attachmentCount));
    out.writeBytes(uint32(recipientCount));
    out.writeBytes(uint32(attachmentCount));
    out.writeBytes(new byte[8]);
    entries.forEach(out::writeBytes);
    return out.toByteArray();
  }

  /** Recipient / attachment property stream: 8-byte header, then entries. */
  private static byte[] storageProperties(List<byte[]> entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[8]);
    entries.forEach(out::writeBytes);
    return out.toByteArray();
  }

  /**
   * One fixed-length property entry: type, id, flags, then eight bytes of
   * value slot (the value itself, zero padded).
   */
  private static byte[] entry(int propertyId, int type, byte[] value) {
    ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putShort((short) type);
    buffer.putShort((short) propertyId);
    buffer.putInt(6);
    buffer.put(value);
    return buffer.array();
  }

  private static byte[] longEntry(int propertyId, int value) {
    return entry(propertyId, TYPE_LONG,
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
  }

  private static byte[] timeEntry(int propertyId, long epochMillis) {
    long filetime = (epochMillis + FILETIME_EPOCH_OFFSET_MILLIS) * 10_000L;
    return entry(propertyId, TYPE_TIME,
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(filetime).array());
  }

  private static byte[] uint32(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  /**
   * PidTagRtfCompressed in its uncompressed ("MELA") form: a 16-byte header
   * of sizes, the signature, and a CRC POI does not check, then raw RTF.
   */
  private static byte[] uncompressedRtfStream(String rtf) {
    byte[] raw = rtf.getBytes(StandardCharsets.US_ASCII);
    ByteBuffer buffer = ByteBuffer.allocate(16 + raw.length).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(raw.length + 12);
    buffer.putInt(raw.length);
    buffer.put(new byte[] {'M', 'E', 'L', 'A'});
    buffer.putInt(0);
    buffer.put(raw);
    return buffer.array();
  }
}
