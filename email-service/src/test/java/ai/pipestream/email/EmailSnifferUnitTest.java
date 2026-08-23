package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.parse.EmailSniffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Format detection reads the bytes and nothing else. These are the cases
 * that decide whether a caller gets an envelope, an UNIMPLEMENTED, or an
 * INVALID_ARGUMENT.
 */
class EmailSnifferUnitTest {

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void recognizesTheOle2Signature() {
    byte[] cfb = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
    assertThat(EmailSniffer.isOle2(cfb, cfb.length)).isTrue();
    assertThat(EmailSniffer.isOle2(cfb, 4))
        .as("a partial signature is not a decision")
        .isFalse();
    assertThat(EmailSniffer.isOle2(ascii("From: a@b\r\n"), 11)).isFalse();
  }

  @Test
  void findsTheHeaderTerminatorForBothLineEndings() {
    byte[] crlf = ascii("From: a@b\r\nSubject: x\r\n\r\nbody");
    assertThat(EmailSniffer.headerBlockLength(crlf, crlf.length, 0)).isEqualTo(23);
    byte[] lf = ascii("From: a@b\nSubject: x\n\nbody");
    assertThat(EmailSniffer.headerBlockLength(lf, lf.length, 0)).isEqualTo(21);
  }

  @Test
  void reportsNoTerminatorUntilTheBlankLineArrives() {
    byte[] partial = ascii("From: a@b\r\nSubject: x\r\n");
    assertThat(EmailSniffer.headerBlockLength(partial, partial.length, 0)).isEqualTo(-1);
  }

  @Test
  void resumesScanningAcrossAChunkBoundary() {
    byte[] whole = ascii("From: a@b\r\nSubject: x\r\n\r\nbody");
    int firstChunk = 22;
    assertThat(EmailSniffer.headerBlockLength(whole, firstChunk, 0)).isEqualTo(-1);
    int resume = EmailSniffer.rescanFrom(firstChunk);
    assertThat(resume).isLessThanOrEqualTo(firstChunk);
    assertThat(EmailSniffer.headerBlockLength(whole, whole.length, resume))
        .as("a terminator straddling the boundary is still found")
        .isEqualTo(23);
  }

  @Test
  void rescanNeverGoesNegative() {
    assertThat(EmailSniffer.rescanFrom(0)).isZero();
    assertThat(EmailSniffer.rescanFrom(1)).isZero();
    assertThat(EmailSniffer.rescanFrom(2)).isZero();
    assertThat(EmailSniffer.rescanFrom(10)).isEqualTo(8);
  }

  @Test
  void acceptsBlocksWithAKnownMailField() {
    byte[] block = ascii("Received: from x\r\nX-Custom: y\r\n");
    assertThat(EmailSniffer.looksLikeHeaderBlock(block, block.length)).isTrue();
  }

  @Test
  void rejectsColonShapedTextWithNoMailField() {
    byte[] block = ascii("key: value\r\nother: thing\r\n");
    assertThat(EmailSniffer.looksLikeHeaderBlock(block, block.length)).isFalse();
  }

  @Test
  void rejectsBlocksThatDoNotStartWithAField() {
    assertThat(EmailSniffer.looksLikeHeaderBlock(ascii("just prose\r\nFrom: a@b\r\n"), 23))
        .isFalse();
    assertThat(EmailSniffer.looksLikeHeaderBlock(ascii("  From: a@b\r\n"), 13))
        .as("a continuation line cannot open a header block")
        .isFalse();
    assertThat(EmailSniffer.looksLikeHeaderBlock(new byte[0], 0)).isFalse();
  }

  @Test
  void acceptsFoldedContinuationLines() {
    byte[] block = ascii("Subject: a very long\r\n  folded subject\r\nTo: a@b\r\n");
    assertThat(EmailSniffer.looksLikeHeaderBlock(block, block.length)).isTrue();
  }

  @Test
  void rejectsFieldNamesWithNonAsciiOrControlCharacters() {
    assertThat(EmailSniffer.looksLikeHeaderBlock(ascii("Fr om: a@b\r\nTo: c@d\r\n"), 21))
        .as("a space inside a field name is not RFC 5322")
        .isFalse();
    assertThat(EmailSniffer.looksLikeHeaderBlock(ascii(": empty name\r\nTo: c@d\r\n"), 23))
        .as("a colon with nothing before it is not a field")
        .isFalse();
  }
}
