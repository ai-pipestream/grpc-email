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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertiesChunk;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
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

    // Transport headers are the only place a .msg keeps genuine RFC 822
    // headers. MAPI properties are not dressed up as headers here: an
    // invented Received line would be a lie about provenance.
    String transport = text(chunks.getMessageHeaders());
    if (!transport.isEmpty()) {
      applyTransportHeaders(transport, info, sink);
    }
    return info.build();
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
    if (info.getInReplyTo().isEmpty()) {
      info.setInReplyTo(HeaderProjection.stripAngles(headerValue(headers, "In-Reply-To")));
    }
    if (info.getContentType().isEmpty()) {
      info.setContentType(headerValue(headers, "Content-Type").trim());
    }
    if (info.getMessageId().isEmpty()) {
      info.setMessageId(HeaderProjection.stripAngles(headerValue(headers, "Message-ID")));
    }
    if (info.getReferencesCount() == 0) {
      for (String reference : headerValue(headers, "References").split("\\s+")) {
        String stripped = HeaderProjection.stripAngles(reference);
        if (!stripped.isEmpty()) {
          info.addReferences(stripped);
        }
      }
    }
  }

  private static String headerValue(InternetHeaders headers, String name) {
    String[] values = headers.getHeader(name);
    return values == null || values.length == 0 || values[0] == null ? "" : values[0];
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
      byte[] payload = payload(chunk);
      String contentType = text(chunk.getAttachMimeTag());
      if (chunk.getAttachmentDirectory() != null) {
        contentType = firstNonEmpty(contentType, "application/vnd.ms-outlook");
        sink.warn("attachment " + index
            + " is an embedded Outlook message; described but not expanded in v1");
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

  private static byte[] payload(AttachmentChunks chunk) {
    ByteChunk data = chunk.getAttachData();
    if (data == null || data.getValue() == null) {
      return new byte[0];
    }
    return data.getValue();
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
    Object value = values.get(0).getValue();
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
    Object value = values.get(0).getValue();
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
