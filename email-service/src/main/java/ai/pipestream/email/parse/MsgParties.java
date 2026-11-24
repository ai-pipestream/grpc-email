package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import jakarta.mail.internet.InternetHeaders;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunk;
import org.apache.poi.hsmf.datatypes.ChunkGroup;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;

/**
 * Resolves the sender and the recipients of a .msg into {@link Address}es.
 *
 * <p>A MAPI store keeps a party under whatever address space it arrived in.
 * For mail that never left an Exchange organisation that is an X.500
 * distinguished name ("EX"), a directory path rather than a mailbox, and the
 * SMTP address sits in a second property, or in none. This class looks in
 * every place the message can hold the SMTP address before it gives up, and
 * when it does give up it says so: the stored name goes out in
 * {@code Address.native_address}, {@code Address.address} stays empty, and
 * the parse carries a warning. Nothing here guesses a mailbox out of a
 * directory name.
 */
final class MsgParties {

  /** MAPI PidTagRecipientType values, per MS-OXOMSG. */
  private static final int RECIPIENT_TYPE_FROM = 0;
  private static final int RECIPIENT_TYPE_TO = 1;
  private static final int RECIPIENT_TYPE_CC = 2;
  private static final int RECIPIENT_TYPE_BCC = 3;

  /** Property ids from MS-OXPROPS; POI names only some of them. */
  private static final int PID_SENT_REPRESENTING_EMAIL_ADDRESS = 0x0065;
  private static final int PID_SENDER_ADDRESS_TYPE = 0x0C1E;
  private static final int PID_SENDER_EMAIL_ADDRESS = 0x0C1F;
  private static final int PID_ADDRESS_TYPE = 0x3002;
  private static final int PID_EMAIL_ADDRESS = 0x3003;
  private static final int PID_SMTP_ADDRESS = 0x39FE;
  private static final int PID_SENDER_SMTP_ADDRESS = 0x5D01;
  private static final int PID_SENT_REPRESENTING_SMTP_ADDRESS = 0x5D02;

  /** How a PidTagSearchKey spells an SMTP party: "SMTP:" then the address. */
  private static final String SEARCH_KEY_SMTP = "SMTP:";

  private MsgParties() {}

  /**
   * The message's sender, or null when the message names none at all.
   *
   * <p>The SMTP address is taken from the first of these that holds one:
   * PidTagSenderSmtpAddress; PidTagSenderEmailAddress when it is itself an
   * addr-spec; PidTagSentRepresentingSmtpAddress, but only when the
   * represented party is stored under the very same address as the sender
   * (a delegate's principal is somebody else); the Sender header of the
   * transport block, or its From header when it has no Sender.
   *
   * @param transportHeaders PidTagTransportMessageHeaders, or empty
   */
  static Address sender(Chunks chunks, String transportHeaders, ParseSink sink) {
    String name = text(chunks.getDisplayFromChunk());
    String stored = string(chunks, PID_SENDER_EMAIL_ADDRESS);
    String type = string(chunks, PID_SENDER_ADDRESS_TYPE);
    String mailbox = firstMailbox(string(chunks, PID_SENDER_SMTP_ADDRESS), stored);
    if (mailbox.isEmpty()
        && !stored.isEmpty()
        && stored.equalsIgnoreCase(string(chunks, PID_SENT_REPRESENTING_EMAIL_ADDRESS))) {
      mailbox = firstMailbox(string(chunks, PID_SENT_REPRESENTING_SMTP_ADDRESS));
    }
    if (mailbox.isEmpty()) {
      mailbox = transportSender(transportHeaders);
    }
    String nativeAddress = isMailbox(stored) ? "" : stored;
    if (name.isEmpty() && mailbox.isEmpty() && nativeAddress.isEmpty()) {
      return null;
    }
    if (mailbox.isEmpty() && !nativeAddress.isEmpty()) {
      sink.warn("the sender is stored under " + describe(type) + " and the message holds no"
          + " SMTP address for it; Address.address is empty and native_address carries the"
          + " stored value");
    }
    return address(AddressRole.ADDRESS_ROLE_FROM, name, mailbox, nativeAddress, type);
  }

  /**
   * The message's recipients, in storage order.
   *
   * <p>The SMTP address is taken from the first of these that holds one:
   * PidTagSmtpAddress; PidTagEmailAddress when it is itself an addr-spec; a
   * display name that is nothing but an address; a PidTagSearchKey of the
   * "SMTP:" kind. A recipient storage with neither a name nor any address
   * is left out and counted in a warning rather than emitted as an Address
   * with nothing in it.
   */
  static List<Address> recipients(RecipientChunks[] found, ParseSink sink) {
    List<Address> addresses = new ArrayList<>();
    int unresolved = 0;
    int empty = 0;
    for (RecipientChunks recipient : found) {
      String name = trimmed(recipient.getRecipientName());
      String stored = string(recipient, PID_EMAIL_ADDRESS);
      String type = string(recipient, PID_ADDRESS_TYPE);
      String mailbox = firstMailbox(
          string(recipient, PID_SMTP_ADDRESS), stored, unquoted(name), searchKeyMailbox(recipient));
      String nativeAddress = isMailbox(stored) ? "" : stored;
      if (name.isEmpty() && mailbox.isEmpty() && nativeAddress.isEmpty()) {
        empty++;
        continue;
      }
      if (mailbox.isEmpty() && !nativeAddress.isEmpty()) {
        unresolved++;
      }
      addresses.add(address(role(recipient), name, mailbox, nativeAddress, type));
    }
    if (unresolved > 0) {
      sink.warn(unresolved + " of " + found.length + " recipients are stored under a non-SMTP"
          + " address (an Exchange directory name, usually) and the message holds no SMTP"
          + " address for them; their Address.address is empty and native_address carries the"
          + " stored value");
    }
    if (empty > 0) {
      sink.warn(empty + " of " + found.length + " recipient entries carry neither a name nor"
          + " an address and were left out");
    }
    return addresses;
  }

  private static Address address(
      AddressRole role, String name, String mailbox, String nativeAddress, String type) {
    return HeaderProjection.address(role, name, mailbox).toBuilder()
        .setNativeAddress(nativeAddress)
        .setAddressType(type)
        .build();
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

  /**
   * The one mailbox the transport headers name as the sender: Sender when
   * the block has one (that is what PidTagSender* mirrors), else From. A
   * header that names several mailboxes, or none, settles nothing.
   */
  private static String transportSender(String transportHeaders) {
    if (transportHeaders.isEmpty()) {
      return "";
    }
    InternetHeaders headers;
    try {
      headers = new InternetHeaders(
          new ByteArrayInputStream(transportHeaders.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception unreadable) {
      // MsgParser reports an unreadable transport block when it projects it.
      return "";
    }
    String[] sender = headers.getHeader("Sender");
    String[] from = headers.getHeader("From");
    String[] chosen = sender != null && sender.length > 0 ? sender : from;
    if (chosen == null || chosen.length != 1 || chosen[0] == null) {
      return "";
    }
    List<Address> parsed =
        HeaderProjection.parseAddressList(chosen[0], AddressRole.ADDRESS_ROLE_FROM);
    return parsed.size() == 1 ? firstMailbox(parsed.getFirst().getAddress()) : "";
  }

  /** The SMTP address inside a search key of the "SMTP:" kind, else empty. */
  private static String searchKeyMailbox(RecipientChunks recipient) {
    ByteChunk key = recipient.getRecipientSearchChunk();
    if (key == null || key.getValue() == null) {
      return "";
    }
    String text = key.getAs7bitString();
    int at = text == null ? -1 : text.indexOf(SEARCH_KEY_SMTP);
    // The key ends in a NUL, which trim() takes off with the whitespace.
    return at < 0 ? "" : text.substring(at + SEARCH_KEY_SMTP.length()).trim();
  }

  /** A display name some clients write as a quoted bare address. */
  private static String unquoted(String name) {
    return name.length() > 1 && name.startsWith("'") && name.endsWith("'")
        ? name.substring(1, name.length() - 1)
        : name;
  }

  private static String firstMailbox(String... candidates) {
    for (String candidate : candidates) {
      if (isMailbox(candidate)) {
        return candidate;
      }
    }
    return "";
  }

  /**
   * True for something shaped like an addr-spec. A distinguished name starts
   * with a slash and may well contain an at sign further in, so the at sign
   * alone decides nothing.
   */
  static boolean isMailbox(String value) {
    int at = value.indexOf('@');
    return at > 0
        && at < value.length() - 1
        && !value.startsWith("/")
        && value.chars().noneMatch(Character::isWhitespace);
  }

  private static String describe(String type) {
    return type.isEmpty() ? "a non-SMTP address" : "a non-SMTP address (type " + type + ")";
  }

  /** The first non-blank string chunk with this property id, trimmed. */
  private static String string(ChunkGroup group, int propertyId) {
    for (Chunk chunk : group.getChunks()) {
      if (chunk.getChunkId() == propertyId && chunk instanceof StringChunk string) {
        String value = trimmed(string.getValue());
        if (!value.isEmpty()) {
          return value;
        }
      }
    }
    return "";
  }

  private static String text(StringChunk chunk) {
    return chunk == null ? "" : trimmed(chunk.getValue());
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }
}
