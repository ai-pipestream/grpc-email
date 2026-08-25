package ai.pipestream.email.parse;

import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import com.google.protobuf.Timestamp;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Turns an RFC 822 header block into the typed EmailInfo event. Everything
 * here is derived from headers alone, which is what lets the server emit the
 * envelope while the body is still being uploaded.
 *
 * <p>Nothing is guessed: a header that will not parse is left out of the
 * typed fields and still appears verbatim in the header tail.
 */
public final class HeaderProjection {

  private HeaderProjection() {}

  /** Parses a raw header block. Throws {@link InvalidEmailException} if it will not read. */
  public static InternetHeaders read(byte[] buffer, int length) {
    try {
      return new InternetHeaders(new ByteArrayInputStream(buffer, 0, length));
    } catch (Exception error) {
      throw new InvalidEmailException("unreadable header block: " + error.getMessage(), error);
    }
  }

  /** Projects a parsed header block into the first event of the stream. */
  public static EmailInfo project(InternetHeaders headers, String documentId, EmailFormat format) {
    EmailInfo.Builder info = EmailInfo.newBuilder()
        .setDocumentId(documentId)
        .setFormat(format)
        .setSubject(decoded(first(headers, "Subject")));

    addAddresses(info, headers, "From", AddressRole.ADDRESS_ROLE_FROM);
    addAddresses(info, headers, "Sender", AddressRole.ADDRESS_ROLE_SENDER);
    addAddresses(info, headers, "Reply-To", AddressRole.ADDRESS_ROLE_REPLY_TO);
    addAddresses(info, headers, "To", AddressRole.ADDRESS_ROLE_TO);
    addAddresses(info, headers, "Cc", AddressRole.ADDRESS_ROLE_CC);
    addAddresses(info, headers, "Bcc", AddressRole.ADDRESS_ROLE_BCC);

    Timestamp date = parseDate(first(headers, "Date"));
    if (date != null) {
      info.setDate(date);
    }
    Timestamp received = receivedDate(headers);
    if (received != null) {
      info.setReceivedDate(received);
    }

    info.setMessageId(stripAngles(first(headers, "Message-ID")));
    List<String> inReplyTo = msgIds(first(headers, "In-Reply-To"));
    info.addAllInReplyToIds(inReplyTo);
    if (!inReplyTo.isEmpty()) {
      info.setInReplyTo(inReplyTo.getFirst());
    }
    // Every References instance, not just the first: a long chain is
    // routinely folded across two header lines, and reading one of them
    // silently loses the older half of the thread.
    info.addAllReferences(msgIds(all(headers, "References")));
    info.setContentType(first(headers, "Content-Type").trim());

    Enumeration<jakarta.mail.Header> all = headers.getAllHeaders();
    while (all.hasMoreElements()) {
      jakarta.mail.Header header = all.nextElement();
      info.addHeaders(
          ai.pipestream.email.v1.Header.newBuilder()
              .setName(header.getName())
              .setValue(decoded(header.getValue()))
              .build());
    }
    return info.build();
  }

  /** Builds one typed address; exported so the MAPI path shares the shape. */
  public static Address address(AddressRole role, String name, String addressSpec) {
    return Address.newBuilder()
        .setRole(role)
        .setName(name == null ? "" : name.trim())
        .setAddress(addressSpec == null ? "" : addressSpec.trim())
        .build();
  }

  /** Converts epoch milliseconds to a protobuf timestamp. */
  public static Timestamp timestamp(long epochMillis) {
    return Timestamp.newBuilder()
        .setSeconds(Math.floorDiv(epochMillis, 1000L))
        .setNanos((int) Math.floorMod(epochMillis, 1000L) * 1_000_000)
        .build();
  }

  /**
   * Splits a msg-id-list header value into its ids, angle brackets stripped.
   *
   * <p>Stripping the outer brackets off the whole value is the trap this
   * exists to avoid: In-Reply-To and References are both {@code 1*msg-id} in
   * RFC 5322, so {@code "<a@x> <b@x>"} treated as one id becomes the single
   * malformed {@code "a@x> <b@x"} and the thread link is lost. Bracketed ids
   * are read as ids wherever they appear, which also tolerates the comma
   * separators some agents emit; a value with no brackets at all is split on
   * whitespace instead, so a bare id still survives.
   *
   * @param value one or more raw header values, each unfolded
   * @return the ids in the order they were written, without empties
   */
  public static List<String> msgIds(String... value) {
    List<String> ids = new ArrayList<>();
    for (String raw : value) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String unfolded = MimeUtility.unfold(raw);
      int cursor = 0;
      boolean bracketed = false;
      while (true) {
        int open = unfolded.indexOf('<', cursor);
        int close = open < 0 ? -1 : unfolded.indexOf('>', open + 1);
        if (close < 0) {
          break;
        }
        bracketed = true;
        String id = unfolded.substring(open + 1, close).trim();
        if (!id.isEmpty()) {
          ids.add(id);
        }
        cursor = close + 1;
      }
      if (bracketed) {
        continue;
      }
      for (String bare : unfolded.trim().split("[\\s,]+")) {
        if (!bare.isEmpty()) {
          ids.add(bare);
        }
      }
    }
    return ids;
  }

  /** Strips the angle brackets RFC 822 wraps msg-ids in. */
  public static String stripAngles(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.length() >= 2 && trimmed.charAt(0) == '<' && trimmed.endsWith(">")) {
      return trimmed.substring(1, trimmed.length() - 1).trim();
    }
    return trimmed;
  }

  /** MIME-word decodes a header value, falling back to the raw text. */
  public static String decoded(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    try {
      return MimeUtility.decodeText(MimeUtility.unfold(value));
    } catch (Exception undecodable) {
      return MimeUtility.unfold(value);
    }
  }

  private static void addAddresses(
      EmailInfo.Builder info, InternetHeaders headers, String field, AddressRole role) {
    String[] values = headers.getHeader(field);
    if (values == null) {
      return;
    }
    for (String value : values) {
      for (Address parsed : parseAddressList(value, role)) {
        info.addAddresses(parsed);
      }
    }
  }

  /**
   * Parses one address-list header. Non-strict parsing keeps malformed
   * mailboxes rather than dropping the whole header; a mailbox that yields
   * neither a name nor an address is discarded.
   */
  public static List<Address> parseAddressList(String value, AddressRole role) {
    List<Address> parsed = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return parsed;
    }
    InternetAddress[] mailboxes;
    try {
      mailboxes = InternetAddress.parseHeader(MimeUtility.unfold(value), false);
    } catch (AddressException malformed) {
      return parsed;
    }
    for (InternetAddress mailbox : mailboxes) {
      String name = decoded(mailbox.getPersonal());
      String spec = mailbox.getAddress() == null ? "" : mailbox.getAddress().trim();
      if (name.isEmpty() && spec.isEmpty()) {
        continue;
      }
      parsed.add(address(role, name, spec));
    }
    return parsed;
  }

  private static String first(InternetHeaders headers, String field) {
    String[] values = headers.getHeader(field);
    return values == null || values.length == 0 || values[0] == null ? "" : values[0];
  }

  /** Every instance of a repeatable header, in the order it was written. */
  private static String[] all(InternetHeaders headers, String field) {
    String[] values = headers.getHeader(field);
    return values == null ? new String[0] : values;
  }

  private static Timestamp parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      Date parsed = new MailDateFormat().parse(MimeUtility.unfold(value).trim());
      return parsed == null ? null : timestamp(parsed.getTime());
    } catch (ParseException | RuntimeException unparseable) {
      return null;
    }
  }

  /**
   * Delivery time from the topmost Received header, whose trailing
   * "; &lt;date&gt;" clause is the time the last hop stamped.
   */
  private static Timestamp receivedDate(InternetHeaders headers) {
    String[] values = headers.getHeader("Received");
    if (values == null || values.length == 0 || values[0] == null) {
      return null;
    }
    String value = MimeUtility.unfold(values[0]);
    int semicolon = value.lastIndexOf(';');
    if (semicolon < 0 || semicolon + 1 >= value.length()) {
      return null;
    }
    return parseDate(value.substring(semicolon + 1));
  }

  /** Lowercases a content type to its "type/subtype", dropping parameters. */
  public static String baseType(String contentType) {
    if (contentType == null) {
      return "";
    }
    int semicolon = contentType.indexOf(';');
    String base = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
    return base.trim().toLowerCase(Locale.ROOT);
  }
}
