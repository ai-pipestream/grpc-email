package ai.pipestream.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.email.v1.Attachment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Content the parser could not read must not come back looking like a clean
 * parse of a message with nothing in it: an encrypted or body-less .eml used
 * to be one attachment, no warnings and STATE_OK, and a .msg attachment with
 * no stored content used to be a named file of size 0.
 */
class UnreadContentUnitTest {

  // --- .eml with no readable body --------------------------------------------

  @Test
  void smimeEnvelopedMessage_isEncrypted_andSaysSo() {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.smimeEnveloped());

    assertEquals(List.of(), parsed.bodies);
    assertEquals(1, parsed.attachments.size(), "the sealed payload is still handed over");
    assertTrue(parsed.encrypted);
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().contains("encrypted"), parsed.warnings.getFirst());
    assertTrue(parsed.warnings.getFirst().contains("application/pkcs7-mime"),
        parsed.warnings.getFirst());
  }

  @Test
  void smimeObjectThatNamesNoType_isTakenAsEncrypted() {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.smime(""));

    assertTrue(parsed.encrypted, "smime.p7m with no smime-type is an enveloped message");
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
  }

  @Test
  void smimeOpaqueSignedMessage_isNotEncrypted_butItsBodyWasNotRead() {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.smime("signed-data"));

    assertFalse(parsed.encrypted, "signed is not sealed");
    assertEquals(List.of(), parsed.bodies);
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().contains("signed-data"), parsed.warnings.getFirst());
  }

  @Test
  void pgpMimeMessage_isEncrypted_andSaysSo() {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.pgpEncrypted());

    assertEquals(List.of(), parsed.bodies);
    assertTrue(parsed.encrypted);
    assertTrue(parsed.warnings.stream().anyMatch(
        warning -> warning.contains("encrypted") && warning.contains("multipart/encrypted")),
        parsed.warnings.toString());
  }

  @Test
  void messageWhoseOnlyTextIsACalendar_saysItHasNoBody() {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.calendarOnly());

    assertEquals(List.of(), parsed.bodies);
    assertFalse(parsed.encrypted);
    assertTrue(parsed.warnings.contains("message carries no text/plain or text/html body"),
        parsed.warnings.toString());
  }

  @Test
  void messageWithABody_isNeitherEncryptedNorWarnedAbout() throws Exception {
    RecordingSink parsed = RecordingSink.eml(EmlFixtures.plainText());

    assertEquals(1, parsed.bodies.size());
    assertFalse(parsed.encrypted);
    assertEquals(List.of(), parsed.warnings);
  }

  // --- .msg attachment with no content ---------------------------------------

  @Test
  void msgAttachmentWithNoStoredContent_isListed_andWarnedAbout() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.attachmentWithoutData());

    assertEquals(1, parsed.attachments.size());
    Attachment attachment = parsed.attachments.getFirst();
    assertEquals(MsgFixtures.ATTACHMENT_NAME, attachment.getFilename());
    assertEquals(0, attachment.getSizeBytes());
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    assertTrue(parsed.warnings.getFirst().startsWith("attachment 0 carries no data"),
        parsed.warnings.getFirst());
  }

  @Test
  void msgAttachmentsWithContent_areNotWarnedAbout() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.full());

    assertEquals(2, parsed.attachments.size());
    assertEquals(List.of(), parsed.warnings);
  }
}
