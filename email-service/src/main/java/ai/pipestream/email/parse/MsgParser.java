package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import jakarta.mail.internet.InternetHeaders;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunk;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.DirectoryChunk;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertiesChunk;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Reads an Outlook .msg (OLE2 compound file holding MAPI properties) through
 * Apache POI's HSMF. Emits the same event vocabulary as the RFC 822 path so
 * a client never has to branch on format.
 *
 * <p>Unlike .eml, the envelope cannot be known early: MAPI properties live
 * in streams scattered through the compound file, so EmailInfo is emitted
 * once the container is open, still ahead of the bodies and attachments.
 */
public final class MsgParser {

  /** Media type of an embedded Outlook message, matching the .msg container. */
  private static final String EMBEDDED_MESSAGE_MIMETYPE = "application/vnd.ms-outlook";

  /** MAPI PidTagRecipientType values, per MS-OXOMSG. */
  private static final int RECIPIENT_TYPE_FROM = 0;
  private static final int RECIPIENT_TYPE_TO = 1;
  private static final int RECIPIENT_TYPE_CC = 2;
  private static final int RECIPIENT_TYPE_BCC = 3;

  private MsgParser() {}

  /**
   * True when this OLE2 container holds MAPI message chunks. A CFB that is
   * something else (a legacy .doc, a Thumbs.db) is not our format.
   */
  public static boolean isMapiMessage(DirectoryNode root) {
    for (Entry entry : root) {
      String name = entry.getName();
      if (name.equals(PropertiesChunk.NAME)
          || name.startsWith(PropertiesChunk.VARIABLE_LENGTH_PROPERTY_PREFIX)
          || name.startsWith(RecipientChunks.PREFIX)
          || name.startsWith(AttachmentChunks.PREFIX)) {
        return true;
      }
    }
    return false;
  }

  /** Parses the whole message, streaming events to the sink as they are known. */
  public static void parse(byte[] bytes, ParseOptions options, ParseSink sink) {
    // Both constructors read eagerly -- POIFSFileSystem parses the header,
    // FAT, and directory, and MAPIMessage walks every stream into chunks --
    // so a truncated or corrupt container fails here and nowhere later. POI
    // signals that with whatever unchecked exception the damaged structure
    // happens to trip, so the boundary is what is caught, not the class.
    try (POIFSFileSystem container = open(bytes)) {
      if (!isMapiMessage(container.getRoot())) {
        throw new UnsupportedFormatException(
            "OLE2 container without MAPI message chunks; not an Outlook .msg");
      }
      MAPIMessage message = read(container);
      try {
        message.guess7BitEncoding();
      } catch (RuntimeException unguessable) {
        sink.warn("could not infer the 7-bit charset of this message: "
            + unguessable.getMessage());
      }
      sink.info(envelope(message, options.documentId(), sink));
      bodies(message, sink);
      attachments(message, options, sink);
    } catch (IOException closeFailed) {
      throw new InvalidEmailException(
          "unreadable Outlook message: " + closeFailed.getMessage(), closeFailed);
    }
  }

  private static POIFSFileSystem open(byte[] bytes) {
    try {
      return new POIFSFileSystem(new ByteArrayInputStream(bytes));
    } catch (Exception unreadable) {
      throw new InvalidEmailException(
          "unreadable OLE2 container: " + unreadable.getMessage(), unreadable);
    }
  }

  private static MAPIMessage read(POIFSFileSystem container) {
    try {
      MAPIMessage message = new MAPIMessage(container);
      message.setReturnNullOnMissingChunk(true);
      return message;
    } catch (Exception unreadable) {
      throw new InvalidEmailException(
          "unreadable MAPI property chunks: " + unreadable.getMessage(), unreadable);
    }
  }

  private static EmailInfo envelope(MAPIMessage message, String documentId, ParseSink sink) {
    Chunks chunks = message.getMainChunks();
    EmailInfo.Builder info = EmailInfo.newBuilder()
        .setDocumentId(documentId)
        .setFormat(EmailFormat.EMAIL_FORMAT_MSG)
        .setSubject(text(chunks.getSubjectChunk()))
        .setMessageId(HeaderProjection.stripAngles(text(chunks.getMessageId())));

    Address sender = senderAddress(chunks);
    if (sender != null) {
      info.addAddresses(sender);
    }
    for (RecipientChunks recipient : recipients(message)) {
      Address address = recipientAddress(recipient);
      if (address != null) {
        info.addAddresses(address);
      }
    }

    Timestamp submitted = time(chunks, MAPIProperty.CLIENT_SUBMIT_TIME);
    if (submitted == null) {
      submitted = fallbackDate(message);
    }
    if (submitted != null) {
      info.setDate(submitted);
    }
    Timestamp delivered = time(chunks, MAPIProperty.MESSAGE_DELIVERY_TIME);
    if (delivered != null) {
      info.setReceivedDate(delivered);
    }

    // Outlook's own threading primitives, read before the transport block
    // because they are the only ones a stored .msg is guaranteed to keep.
    // A message filed out of a mailbox usually has no transport headers at
    // all, and without these it would have no threading whatsoever.
    info.setConversationTopic(text(chunks.getConversationTopic()));
    info.setConversationIndex(hex(binary(chunks, MAPIProperty.CONVERSATION_INDEX)));

    // Transport headers are the only place a .msg keeps genuine RFC 822
    // headers. MAPI properties are not dressed up as headers here: an
    // invented Received line would be a lie about provenance.
    String transport = text(chunks.getMessageHeaders());
    if (!transport.isEmpty()) {
      applyTransportHeaders(transport, info, sink);
    }
    return info.build();
  }

  /**
   * One binary MAPI property, or an empty array when the message had none.
   * Read from the chunk map rather than the property stream: a PT_BINARY
   * property lives in its own stream and only its pointer sits among the
   * fixed-length entries.
   */
  private static byte[] binary(Chunks chunks, MAPIProperty property) {
    Map<MAPIProperty, List<Chunk>> all = chunks.getAll();
    List<Chunk> found = all == null ? null : all.get(property);
    if (found == null) {
      return new byte[0];
    }
    for (Chunk chunk : found) {
      if (chunk instanceof ByteChunk bytes && bytes.getValue() != null) {
        return bytes.getValue();
      }
    }
    return new byte[0];
  }

  /**
   * Lowercase hex of a binary property. PidTagConversationIndex is a packed
   * structure, not text, and its prefix ordering is what makes it useful, so
   * it is carried verbatim rather than decoded into fields this service has
   * no slot for.
   */
  private static String hex(byte[] value) {
    StringBuilder hex = new StringBuilder(value.length * 2);
    for (byte b : value) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }

  private static void applyTransportHeaders(
      String transport, EmailInfo.Builder info, ParseSink sink) {
    InternetHeaders headers;
    try {
      headers = new InternetHeaders(
          new ByteArrayInputStream(transport.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception unreadable) {
      sink.warn("PidTagTransportMessageHeaders could not be parsed: " + unreadable.getMessage());
      return;
    }
    var all = headers.getAllHeaders();
    while (all.hasMoreElements()) {
      jakarta.mail.Header header = all.nextElement();
      info.addHeaders(
          ai.pipestream.email.v1.Header.newBuilder()
              .setName(header.getName())
              .setValue(HeaderProjection.decoded(header.getValue()))
              .build());
    }
    if (info.getInReplyToIdsCount() == 0) {
      List<String> inReplyTo = HeaderProjection.msgIds(headerValue(headers, "In-Reply-To"));
      info.addAllInReplyToIds(inReplyTo);
      if (!inReplyTo.isEmpty()) {
        info.setInReplyTo(inReplyTo.getFirst());
      }
    }
    if (info.getContentType().isEmpty()) {
      info.setContentType(headerValue(headers, "Content-Type").trim());
    }
    if (info.getMessageId().isEmpty()) {
      info.setMessageId(HeaderProjection.stripAngles(headerValue(headers, "Message-ID")));
    }
    if (info.getReferencesCount() == 0) {
      info.addAllReferences(HeaderProjection.msgIds(headerValues(headers, "References")));
    }
  }

  private static String headerValue(InternetHeaders headers, String name) {
    String[] values = headers.getHeader(name);
    return values == null || values.length == 0 || values[0] == null ? "" : values[0];
  }

  private static String[] headerValues(InternetHeaders headers, String name) {
    String[] values = headers.getHeader(name);
    return values == null ? new String[0] : values;
  }

  private static void bodies(MAPIMessage message, ParseSink sink) {
    String plain = body(message::getTextBody);
    String html = body(message::getHtmlBody);
    if (!plain.isEmpty()) {
      sink.bodyPart(
          BodyPart.newBuilder()
              .setPartId("body:plain")
              .setMediaType(BodyMediaType.BODY_MEDIA_TYPE_PLAIN)
              .setContentTypeRaw("text/plain")
              .setText(plain)
              .setSourceProperty("PidTagBody")
              .build());
    }
    if (!html.isEmpty()) {
      sink.bodyPart(
          BodyPart.newBuilder()
              .setPartId("body:html")
              .setMediaType(BodyMediaType.BODY_MEDIA_TYPE_HTML)
              .setContentTypeRaw("text/html")
              .setText(html)
              .setSourceProperty("PidTagHtml")
              .build());
    }
    if (!plain.isEmpty() || !html.isEmpty()) {
      return;
    }
    // HTML wins over RTF; RTF is the last resort, and v1 says so out loud
    // rather than pretending the layout survived.
    String rtf = body(message::getRtfBody);
    if (rtf.isEmpty()) {
      sink.warn("message carries no plain, HTML, or RTF body");
      return;
    }
    String extracted = RtfPlainText.extract(rtf);
    sink.warn("RTF-only body: plain text extracted without layout, tables, or formatting");
    if (!extracted.isEmpty()) {
      sink.bodyPart(
          BodyPart.newBuilder()
              .setPartId("body:rtf")
              .setMediaType(BodyMediaType.BODY_MEDIA_TYPE_PLAIN)
              .setContentTypeRaw("text/plain")
              .setText(extracted)
              .setSourceProperty("PidTagRtfCompressed")
              .build());
    }
  }

  private static void attachments(MAPIMessage message, ParseOptions options, ParseSink sink) {
    AttachmentChunks[] found = message.getAttachmentFiles();
    if (found == null) {
      return;
    }
    for (int index = 0; index < found.length; index++) {
      AttachmentChunks chunk = found[index];
      String filename = firstNonEmpty(
          text(chunk.getAttachLongFileName()), text(chunk.getAttachFileName()));
      String contentId = HeaderProjection.stripAngles(text(chunk.getAttachContentId()));
      byte[] payload = payload(chunk, index, sink);
      String contentType = text(chunk.getAttachMimeTag());
      if (chunk.getAttachmentDirectory() != null) {
        contentType = firstNonEmpty(contentType, EMBEDDED_MESSAGE_MIMETYPE);
        sink.warn("attachment " + index
            + " is an embedded Outlook message; emitted as .msg bytes for the coordinator"
            + " to reparse rather than expanded here");
      }
      if (filename.isEmpty()) {
        sink.warn("attachment " + index + " has no filename");
      }
      Attachment.Builder attachment = Attachment.newBuilder()
          .setIndex(index)
          .setPartId("attach:" + index)
          .setFilename(filename)
          .setContentType(HeaderProjection.baseType(contentType))
          .setSizeBytes(payload.length)
          .setContentId(contentId)
          .setInline(!contentId.isEmpty());
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
  }

  /**
   * The attachment's bytes, from whichever of the two MAPI storage forms the
   * producer used.
   *
   * <p>An ordinary attachment keeps its payload in the PidTagAttachDataBinary
   * chunk and reading it is one call. An embedded message keeps it in
   * PidTagAttachDataObject: a whole nested storage directory, for which
   * {@code getAttachData()} returns nothing at all. Reading only the byte
   * chunk therefore reported every embedded message as size 0 with no
   * payload, which does not defer the nested message so much as destroy it:
   * no coordinator can reparse what it was never given. Forwarded-mail
   * chains, the common shape in support and legal mailboxes, are exactly what
   * was being lost.
   *
   * <p>POI hands back the nested storage as a directory node, so it is
   * written out as a standalone OLE2 container: a real .msg the coordinator
   * can feed straight back into this same service.
   */
  private static byte[] payload(AttachmentChunks chunk, int index, ParseSink sink) {
    ByteChunk data = chunk.getAttachData();
    if (data != null && data.getValue() != null) {
      return data.getValue();
    }
    DirectoryChunk directory = chunk.getAttachmentDirectory();
    if (directory == null || directory.getDirectory() == null) {
      return new byte[0];
    }
    try (POIFSFileSystem nested = new POIFSFileSystem();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      EntryUtils.copyNodes(directory.getDirectory(), nested.getRoot());
      nested.writeFilesystem(out);
      return out.toByteArray();
    } catch (IOException | RuntimeException unwritable) {
      // A nested storage that will not round-trip is a degraded attachment,
      // not a failed parse: the rest of the message is still good.
      sink.warn("attachment " + index + " is an embedded Outlook message whose storage could"
          + " not be repacked: " + unwritable.getMessage());
      return new byte[0];
    }
  }

  private static RecipientChunks[] recipients(MAPIMessage message) {
    RecipientChunks[] found = message.getRecipientDetailsChunks();
    return found == null ? new RecipientChunks[0] : found;
  }

  private static Address recipientAddress(RecipientChunks recipient) {
    String name = nullToEmpty(recipient.getRecipientName());
    String email = nullToEmpty(recipient.getRecipientEmailAddress());
    if (name.isEmpty() && email.isEmpty()) {
      return null;
    }
    return HeaderProjection.address(role(recipient), name, email.contains("@") ? email : "");
  }

  private static AddressRole role(RecipientChunks recipient) {
    Map<MAPIProperty, List<PropertyValue>> properties = recipient.getProperties();
    List<PropertyValue> values =
        properties == null ? null : properties.get(MAPIProperty.RECIPIENT_TYPE);
    if (values == null || values.isEmpty()) {
      // MS-OXOMSG makes PidTagRecipientType mandatory; when a producer omits
      // it, "to" is the only defensible reading of a stored recipient.
      return AddressRole.ADDRESS_ROLE_TO;
    }
    Object value = values.getFirst().getValue();
    int type = value instanceof Number number ? number.intValue() : RECIPIENT_TYPE_TO;
    return switch (type) {
      case RECIPIENT_TYPE_FROM -> AddressRole.ADDRESS_ROLE_FROM;
      case RECIPIENT_TYPE_TO -> AddressRole.ADDRESS_ROLE_TO;
      case RECIPIENT_TYPE_CC -> AddressRole.ADDRESS_ROLE_CC;
      case RECIPIENT_TYPE_BCC -> AddressRole.ADDRESS_ROLE_BCC;
      default -> AddressRole.ADDRESS_ROLE_TO;
    };
  }

  private static Address senderAddress(Chunks chunks) {
    String name = text(chunks.getDisplayFromChunk());
    String email = text(chunks.getEmailFromChunk());
    // Exchange stores internal senders as an X.500 distinguished name; that
    // is a directory path, not a mailbox, so it does not go in `address`.
    String mailbox = email.contains("@") ? email : "";
    if (name.isEmpty() && mailbox.isEmpty()) {
      return null;
    }
    return HeaderProjection.address(AddressRole.ADDRESS_ROLE_FROM, name, mailbox);
  }

  private static Timestamp time(Chunks chunks, MAPIProperty property) {
    Map<MAPIProperty, List<PropertyValue>> properties = chunks.getProperties();
    List<PropertyValue> values = properties == null ? null : properties.get(property);
    if (values == null || values.isEmpty()) {
      return null;
    }
    Object value = values.getFirst().getValue();
    return value instanceof Calendar calendar
        ? HeaderProjection.timestamp(calendar.getTimeInMillis())
        : null;
  }

  private static Timestamp fallbackDate(MAPIMessage message) {
    try {
      Calendar date = message.getMessageDate();
      return date == null ? null : HeaderProjection.timestamp(date.getTimeInMillis());
    } catch (Exception missing) {
      return null;
    }
  }

  /** Reads one body accessor, treating a missing chunk as "no such body". */
  private static String body(BodyAccessor accessor) {
    try {
      String value = accessor.get();
      return value == null ? "" : value;
    } catch (Exception missing) {
      return "";
    }
  }

  /** A MAPIMessage body getter, all of which declare ChunkNotFoundException. */
  @FunctionalInterface
  private interface BodyAccessor {
    String get() throws Exception;
  }

  private static String text(StringChunk chunk) {
    return chunk == null ? "" : nullToEmpty(chunk.getValue());
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String firstNonEmpty(String first, String second) {
    return first.isEmpty() ? second : first;
  }
}
