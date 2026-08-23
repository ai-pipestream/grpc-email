package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import ai.pipestream.email.parse.HeaderProjection;
import ai.pipestream.email.parse.InvalidEmailException;
import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The header-to-EmailInfo projection sits directly on Jakarta Mail's parsing
 * (InternetHeaders, InternetAddress, MailDateFormat, MimeUtility), so these
 * tests double as the canary for angus-mail upgrades: if a new release
 * changes what a decoded subject, a parsed mailbox, or an RFC 822 date looks
 * like, they fail here rather than on the wire.
 */
class HeaderProjectionUnitTest {

  private static EmailInfo project(String block) {
    byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
    return HeaderProjection.project(
        HeaderProjection.read(bytes, bytes.length), "doc-1", EmailFormat.EMAIL_FORMAT_EML);
  }

  @Test
  void projectsTheFullEnvelopeFromHeadersAlone() {
    EmailInfo info = project("""
        From: "Ana Ruiz" <ana@example.com>\r
        To: bob@example.com, "Cara" <cara@example.com>\r
        Cc: dee@example.com\r
        Subject: =?UTF-8?Q?Hola_se=C3=B1or?=\r
        Date: Tue, 14 May 2024 10:30:00 +0000\r
        Received: from mx.example.com by mail.example.com;\r
          Tue, 14 May 2024 10:30:05 +0000\r
        Message-ID: <mid-1@example.com>\r
        In-Reply-To: <mid-0@example.com>\r
        References: <root@example.com> <mid-0@example.com>\r
        Content-Type: text/plain; charset=utf-8\r
        X-Custom: kept\r
        \r
        """);

    assertThat(info.getDocumentId()).isEqualTo("doc-1");
    assertThat(info.getFormat()).isEqualTo(EmailFormat.EMAIL_FORMAT_EML);
    assertThat(info.getSubject())
        .as("encoded words decode before they reach the wire")
        .isEqualTo("Hola señor");
    assertThat(info.getDate().getSeconds())
        .isEqualTo(Instant.parse("2024-05-14T10:30:00Z").getEpochSecond());
    assertThat(info.getReceivedDate().getSeconds())
        .as("the topmost Received header's date clause is the delivery time")
        .isEqualTo(Instant.parse("2024-05-14T10:30:05Z").getEpochSecond());
    assertThat(info.getMessageId()).isEqualTo("mid-1@example.com");
    assertThat(info.getInReplyTo()).isEqualTo("mid-0@example.com");
    assertThat(info.getReferencesList())
        .containsExactly("root@example.com", "mid-0@example.com");
    assertThat(info.getContentType()).isEqualTo("text/plain; charset=utf-8");

    assertThat(info.getAddressesList())
        .extracting(Address::getRole, Address::getName, Address::getAddress)
        .containsExactly(
            tuple(AddressRole.ADDRESS_ROLE_FROM, "Ana Ruiz", "ana@example.com"),
            tuple(AddressRole.ADDRESS_ROLE_TO, "", "bob@example.com"),
            tuple(AddressRole.ADDRESS_ROLE_TO, "Cara", "cara@example.com"),
            tuple(AddressRole.ADDRESS_ROLE_CC, "", "dee@example.com"));

    assertThat(info.getHeadersList())
        .as("the lossless tail keeps every header, typed home or not")
        .anyMatch(h -> h.getName().equals("X-Custom") && h.getValue().equals("kept"));
  }

  @Test
  void unparseableDateIsAbsentNotInvented() {
    EmailInfo info = project("From: a@example.com\r\nDate: not a date\r\n\r\n");
    assertThat(info.hasDate())
        .as("a Date that will not parse leaves the typed field unset")
        .isFalse();
    assertThat(info.getHeadersList())
        .as("the raw header still rides the lossless tail")
        .anyMatch(h -> h.getName().equals("Date") && h.getValue().equals("not a date"));
  }

  @Test
  void malformedMailboxIsKeptVerbatimRatherThanDroppingTheHeader() {
    EmailInfo info = project("From: <<<\r\nSubject: still here\r\n\r\n");
    assertThat(info.getAddressesList())
        .as("non-strict parsing keeps what the sender wrote instead of losing the header")
        .extracting(Address::getRole, Address::getAddress)
        .containsExactly(tuple(AddressRole.ADDRESS_ROLE_FROM, "<<<"));
    assertThat(info.getSubject()).isEqualTo("still here");
  }

  @Test
  void parseAddressListParsesMultipleMailboxesAndSurvivesBlanks() {
    List<Address> parsed = HeaderProjection.parseAddressList(
        "\"Eve\" <eve@example.com>, frank@example.com", AddressRole.ADDRESS_ROLE_TO);
    assertThat(parsed)
        .extracting(Address::getName, Address::getAddress)
        .containsExactly(
            tuple("Eve", "eve@example.com"),
            tuple("", "frank@example.com"));
    assertThat(HeaderProjection.parseAddressList(null, AddressRole.ADDRESS_ROLE_TO)).isEmpty();
    assertThat(HeaderProjection.parseAddressList("  ", AddressRole.ADDRESS_ROLE_TO)).isEmpty();
  }

  @Test
  void stripAnglesHandlesEveryShape() {
    assertThat(HeaderProjection.stripAngles("<id@example.com>")).isEqualTo("id@example.com");
    assertThat(HeaderProjection.stripAngles("  <id@example.com>  ")).isEqualTo("id@example.com");
    assertThat(HeaderProjection.stripAngles("id@example.com")).isEqualTo("id@example.com");
    assertThat(HeaderProjection.stripAngles("<>")).isEmpty();
    assertThat(HeaderProjection.stripAngles(null)).isEmpty();
    assertThat(HeaderProjection.stripAngles("<unclosed")).isEqualTo("<unclosed");
  }

  @Test
  void baseTypeLowercasesAndDropsParameters() {
    assertThat(HeaderProjection.baseType("Text/HTML; charset=UTF-8")).isEqualTo("text/html");
    assertThat(HeaderProjection.baseType(" text/plain ")).isEqualTo("text/plain");
    assertThat(HeaderProjection.baseType(null)).isEmpty();
  }

  @Test
  void timestampSplitsMillisWithFloorSemantics() {
    assertThat(HeaderProjection.timestamp(1500).getSeconds()).isEqualTo(1);
    assertThat(HeaderProjection.timestamp(1500).getNanos()).isEqualTo(500_000_000);
    assertThat(HeaderProjection.timestamp(-1500).getSeconds())
        .as("pre-epoch instants floor toward negative infinity")
        .isEqualTo(-2);
    assertThat(HeaderProjection.timestamp(-1500).getNanos()).isEqualTo(500_000_000);
  }

  @Test
  void decodedFallsBackToTheRawTextWhenTheWordIsBroken() {
    assertThat(HeaderProjection.decoded("=?UTF-8?Q?caf=C3=A9?=")).isEqualTo("café");
    assertThat(HeaderProjection.decoded("plain text")).isEqualTo("plain text");
    assertThat(HeaderProjection.decoded(null)).isEmpty();
    assertThat(HeaderProjection.decoded("folded\r\n value"))
        .as("folded headers unfold before decoding")
        .isEqualTo("folded value");
  }

  @Test
  void unreadableHeaderBlockIsInvalidEmail() {
    assertThatExceptionOfType(InvalidEmailException.class)
        .isThrownBy(() -> HeaderProjection.read(null, 0));
  }
}
