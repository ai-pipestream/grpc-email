package ai.pipestream.email;

import static ai.pipestream.email.MsgFixtures.DISPLAY_NAME_PROPERTY;
import static ai.pipestream.email.MsgFixtures.EMAIL_ADDRESS_PROPERTY;
import static ai.pipestream.email.MsgFixtures.PID_ADDRESS_TYPE;
import static ai.pipestream.email.MsgFixtures.PID_SENDER_ADDRESS_TYPE;
import static ai.pipestream.email.MsgFixtures.PID_SENDER_SMTP_ADDRESS;
import static ai.pipestream.email.MsgFixtures.PID_SENT_REPRESENTING_EMAIL_ADDRESS;
import static ai.pipestream.email.MsgFixtures.PID_SENT_REPRESENTING_SMTP_ADDRESS;
import static ai.pipestream.email.MsgFixtures.PID_SMTP_ADDRESS;
import static ai.pipestream.email.MsgFixtures.RECIPIENT_CC;
import static ai.pipestream.email.MsgFixtures.RECIPIENT_TO;
import static ai.pipestream.email.MsgFixtures.SENDER_EMAIL_PROPERTY;
import static ai.pipestream.email.MsgFixtures.SENDER_NAME_PROPERTY;
import static ai.pipestream.email.MsgFixtures.TRANSPORT_HEADERS_PROPERTY;
import static ai.pipestream.email.MsgFixtures.property;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.email.MsgFixtures.StoredRecipient;
import ai.pipestream.email.MsgFixtures.StringProperty;
import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where a .msg keeps a party's SMTP address, and what reaches the caller
 * when it keeps none. Mail that never left an Exchange organisation stores
 * its sender and recipients under an X.500 directory name; the address used
 * to be dropped without a word, and a recipient with no display name went
 * out as an Address with nothing in it.
 */
class MsgPartyAddressUnitTest {

  private static final String SENDER_DN =
      "/O=EXAMPLE/OU=EXCHANGE ADMINISTRATIVE GROUP (FYDIBOHF23SPDLT)/CN=RECIPIENTS/CN=CLERK";
  private static final String OTHER_DN =
      "/O=EXAMPLE/OU=EXCHANGE ADMINISTRATIVE GROUP (FYDIBOHF23SPDLT)/CN=RECIPIENTS/CN=JUDGE";
  private static final String ADA_DN =
      "/O=EXAMPLE/OU=EXCHANGE ADMINISTRATIVE GROUP (FYDIBOHF23SPDLT)/CN=RECIPIENTS/CN=ADA";

  private static final StoredRecipient ADA = StoredRecipient.of(RECIPIENT_TO,
      property(DISPLAY_NAME_PROPERTY, MsgFixtures.TO_NAME),
      property(EMAIL_ADDRESS_PROPERTY, MsgFixtures.TO_EMAIL));

  // --- sender ---------------------------------------------------------------

  @Test
  void exchangeSender_takesItsAddressFromTheSmtpProperty() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(PID_SENDER_ADDRESS_TYPE, "EX"),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN),
        property(PID_SENDER_SMTP_ADDRESS, MsgFixtures.SENDER_EMAIL));

    Address from = from(parsed);
    assertEquals(MsgFixtures.SENDER_NAME, from.getName());
    assertEquals(MsgFixtures.SENDER_EMAIL, from.getAddress());
    assertEquals(SENDER_DN, from.getNativeAddress(), "the directory name is kept beside it");
    assertEquals("EX", from.getAddressType());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void exchangeSender_withNoSmtpAddressAnywhere_keepsTheDirectoryNameAndWarns()
      throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(PID_SENDER_ADDRESS_TYPE, "EX"),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN));

    Address from = from(parsed);
    assertEquals(MsgFixtures.SENDER_NAME, from.getName());
    assertEquals("", from.getAddress(), "a directory path is not a mailbox");
    assertEquals(SENDER_DN, from.getNativeAddress());
    assertEquals("EX", from.getAddressType());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().contains("sender"), parsed.warnings.getFirst());
    assertTrue(parsed.warnings.getFirst().contains("EX"), parsed.warnings.getFirst());
  }

  @Test
  void exchangeSender_withNoDisplayName_isStillEmitted() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN));

    Address from = from(parsed);
    assertEquals("", from.getName());
    assertEquals("", from.getAddress());
    assertEquals(SENDER_DN, from.getNativeAddress());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void exchangeSender_usesTheRepresentedSmtpAddress_whenItIsTheSameDirectoryEntry()
      throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN),
        property(PID_SENT_REPRESENTING_EMAIL_ADDRESS, SENDER_DN.toLowerCase()),
        property(PID_SENT_REPRESENTING_SMTP_ADDRESS, MsgFixtures.SENDER_EMAIL));

    assertEquals(MsgFixtures.SENDER_EMAIL, from(parsed).getAddress());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void exchangeSender_doesNotBorrowTheAddressOfSomebodyElseItSentFor() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN),
        property(PID_SENT_REPRESENTING_EMAIL_ADDRESS, OTHER_DN),
        property(PID_SENT_REPRESENTING_SMTP_ADDRESS, "judge@example.gov"));

    assertEquals("", from(parsed).getAddress(), "the judge's mailbox is not the clerk's");
    assertEquals(SENDER_DN, from(parsed).getNativeAddress());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void exchangeSender_fallsBackToTheTransportFromHeader() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN),
        property(TRANSPORT_HEADERS_PROPERTY,
            "From: Clerk of Court <" + MsgFixtures.SENDER_EMAIL + ">\r\n"
                + "To: " + MsgFixtures.TO_EMAIL + "\r\n"));

    assertEquals(MsgFixtures.SENDER_EMAIL, from(parsed).getAddress());
    assertEquals(SENDER_DN, from(parsed).getNativeAddress());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void exchangeSender_prefersTheTransportSenderHeaderOverFrom() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN),
        property(TRANSPORT_HEADERS_PROPERTY,
            "From: The Judge <judge@example.gov>\r\n"
                + "Sender: Clerk of Court <" + MsgFixtures.SENDER_EMAIL + ">\r\n"));

    assertEquals(MsgFixtures.SENDER_EMAIL, from(parsed).getAddress());
  }

  @Test
  void exchangeSender_aFromHeaderWithTwoMailboxesSettlesNothing() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, SENDER_DN),
        property(TRANSPORT_HEADERS_PROPERTY,
            "From: a@example.gov, b@example.gov\r\n"));

    assertEquals("", from(parsed).getAddress());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void directoryNameCarryingAnAtSign_isNotMistakenForAMailbox() throws Exception {
    String dn = "/o=ExchangeLabs/ou=Exchange Administrative Group/cn=Recipients/cn=clerk@example";
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, dn));

    assertEquals("", from(parsed).getAddress());
    assertEquals(dn, from(parsed).getNativeAddress());
  }

  @Test
  void smtpSender_isUnchanged_andHasNoNativeAddress() throws Exception {
    RecordingSink parsed = parse(List.of(ADA),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(PID_SENDER_ADDRESS_TYPE, "SMTP"),
        property(SENDER_EMAIL_PROPERTY, MsgFixtures.SENDER_EMAIL));

    Address from = from(parsed);
    assertEquals(MsgFixtures.SENDER_EMAIL, from.getAddress());
    assertEquals("", from.getNativeAddress());
    assertEquals("SMTP", from.getAddressType());
    assertEquals(List.of(), parsed.warnings);
  }

  // --- recipients -----------------------------------------------------------

  @Test
  void exchangeRecipient_takesItsAddressFromTheSmtpProperty() throws Exception {
    RecordingSink parsed = parseRecipients(StoredRecipient.of(RECIPIENT_TO,
        property(DISPLAY_NAME_PROPERTY, MsgFixtures.TO_NAME),
        property(PID_ADDRESS_TYPE, "EX"),
        property(EMAIL_ADDRESS_PROPERTY, ADA_DN),
        property(PID_SMTP_ADDRESS, MsgFixtures.TO_EMAIL)));

    Address to = recipients(parsed).getFirst();
    assertEquals(AddressRole.ADDRESS_ROLE_TO, to.getRole());
    assertEquals(MsgFixtures.TO_NAME, to.getName());
    assertEquals(MsgFixtures.TO_EMAIL, to.getAddress());
    assertEquals(ADA_DN, to.getNativeAddress());
    assertEquals("EX", to.getAddressType());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void exchangeRecipient_withNoSmtpAddress_keepsTheWholeDirectoryNameAndWarns()
      throws Exception {
    RecordingSink parsed = parseRecipients(
        ADA,
        StoredRecipient.of(RECIPIENT_CC,
            property(DISPLAY_NAME_PROPERTY, MsgFixtures.CC_NAME),
            property(PID_ADDRESS_TYPE, "EX"),
            property(EMAIL_ADDRESS_PROPERTY, ADA_DN)));

    Address cc = recipients(parsed).get(1);
    assertEquals(AddressRole.ADDRESS_ROLE_CC, cc.getRole());
    assertEquals(MsgFixtures.CC_NAME, cc.getName());
    assertEquals("", cc.getAddress());
    assertEquals(ADA_DN, cc.getNativeAddress(), "not cut at the first /CN=");
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().startsWith("1 of 2 recipients"),
        parsed.warnings.getFirst());
  }

  @Test
  void exchangeRecipient_withNoDisplayName_isNotAnEmptyAddress() throws Exception {
    RecordingSink parsed = parseRecipients(StoredRecipient.of(RECIPIENT_TO,
        property(EMAIL_ADDRESS_PROPERTY, ADA_DN)));

    List<Address> recipients = recipients(parsed);
    assertEquals(1, recipients.size());
    assertFalse(recipients.getFirst().getNativeAddress().isEmpty(),
        "an Address must say something about the party");
    assertEquals(ADA_DN, recipients.getFirst().getNativeAddress());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void exchangeRecipient_takesItsAddressFromAnSmtpSearchKey() throws Exception {
    RecordingSink parsed = parseRecipients(StoredRecipient.of(RECIPIENT_TO,
            property(DISPLAY_NAME_PROPERTY, MsgFixtures.TO_NAME),
            property(EMAIL_ADDRESS_PROPERTY, ADA_DN))
        .withSearchKey("SMTP:ADA@EXAMPLE.COM"));

    assertEquals("ADA@EXAMPLE.COM", recipients(parsed).getFirst().getAddress());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void exchangeSearchKey_isNotAnAddress() throws Exception {
    RecordingSink parsed = parseRecipients(StoredRecipient.of(RECIPIENT_TO,
            property(DISPLAY_NAME_PROPERTY, MsgFixtures.TO_NAME),
            property(EMAIL_ADDRESS_PROPERTY, ADA_DN))
        .withSearchKey("EX:" + ADA_DN));

    assertEquals("", recipients(parsed).getFirst().getAddress());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void recipientNamedByABareQuotedAddress_usesIt() throws Exception {
    RecordingSink parsed = parseRecipients(StoredRecipient.of(RECIPIENT_TO,
        property(DISPLAY_NAME_PROPERTY, "'" + MsgFixtures.TO_EMAIL + "'")));

    assertEquals(MsgFixtures.TO_EMAIL, recipients(parsed).getFirst().getAddress());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void recipientWithOnlyADisplayName_isKeptWithoutAWarning() throws Exception {
    RecordingSink parsed = parseRecipients(StoredRecipient.of(RECIPIENT_TO,
        property(DISPLAY_NAME_PROPERTY, "Undisclosed recipients")));

    Address to = recipients(parsed).getFirst();
    assertEquals("Undisclosed recipients", to.getName());
    assertEquals("", to.getAddress());
    assertEquals("", to.getNativeAddress());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void recipientStorageWithNothingInIt_isLeftOutAndCounted() throws Exception {
    RecordingSink parsed = parseRecipients(ADA, StoredRecipient.of(RECIPIENT_TO));

    assertEquals(1, recipients(parsed).size());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().startsWith("1 of 2 recipient entries"),
        parsed.warnings.getFirst());
  }

  @Test
  void noAddressOnTheMessageIsEverEmpty() throws Exception {
    RecordingSink parsed = parseRecipients(
        ADA,
        StoredRecipient.of(RECIPIENT_TO, property(EMAIL_ADDRESS_PROPERTY, ADA_DN)),
        StoredRecipient.of(RECIPIENT_CC, property(PID_ADDRESS_TYPE, "EX")),
        StoredRecipient.of(RECIPIENT_CC));

    for (Address address : parsed.infos.getFirst().getAddressesList()) {
      assertFalse(
          address.getName().isEmpty()
              && address.getAddress().isEmpty()
              && address.getNativeAddress().isEmpty(),
          "empty Address emitted: " + address);
    }
  }

  @Test
  void smtpRecipients_areUnchanged() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.full());

    List<Address> recipients = recipients(parsed);
    assertEquals(
        List.of(MsgFixtures.TO_EMAIL, MsgFixtures.CC_EMAIL, MsgFixtures.BCC_EMAIL),
        recipients.stream().map(Address::getAddress).toList());
    assertTrue(recipients.stream().allMatch(address -> address.getNativeAddress().isEmpty()));
    assertEquals(MsgFixtures.SENDER_EMAIL, from(parsed).getAddress());
    assertEquals(List.of(), parsed.warnings);
  }

  // --- helpers --------------------------------------------------------------

  private static RecordingSink parse(List<StoredRecipient> recipients, StringProperty... message)
      throws Exception {
    return RecordingSink.msg(MsgFixtures.parties(List.of(message), recipients));
  }

  private static RecordingSink parseRecipients(StoredRecipient... recipients) throws Exception {
    return parse(List.of(recipients),
        property(SENDER_NAME_PROPERTY, MsgFixtures.SENDER_NAME),
        property(SENDER_EMAIL_PROPERTY, MsgFixtures.SENDER_EMAIL));
  }

  private static Address from(RecordingSink parsed) {
    List<Address> found = new ArrayList<>();
    for (Address address : parsed.infos.getFirst().getAddressesList()) {
      if (address.getRole() == AddressRole.ADDRESS_ROLE_FROM) {
        found.add(address);
      }
    }
    assertEquals(1, found.size(), "exactly one sender: " + found);
    return found.getFirst();
  }

  private static List<Address> recipients(RecordingSink parsed) {
    return parsed.infos.getFirst().getAddressesList().stream()
        .filter(address -> address.getRole() != AddressRole.ADDRESS_ROLE_FROM)
        .toList();
  }
}
