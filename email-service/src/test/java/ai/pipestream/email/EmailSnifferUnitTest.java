package ai.pipestream.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    assertTrue(EmailSniffer.isOle2(cfb, cfb.length));
    assertFalse(EmailSniffer.isOle2(cfb, 4), "a partial signature is not a decision");
    assertFalse(EmailSniffer.isOle2(ascii("From: a@b\r\n"), 11));
  }

  @Test
  void findsTheHeaderTerminatorForBothLineEndings() {
    byte[] crlf = ascii("From: a@b\r\nSubject: x\r\n\r\nbody");
    assertEquals(23, EmailSniffer.headerBlockLength(crlf, crlf.length, 0));
    byte[] lf = ascii("From: a@b\nSubject: x\n\nbody");
    assertEquals(21, EmailSniffer.headerBlockLength(lf, lf.length, 0));
  }

  @Test
  void reportsNoTerminatorUntilTheBlankLineArrives() {
    byte[] partial = ascii("From: a@b\r\nSubject: x\r\n");
    assertEquals(-1, EmailSniffer.headerBlockLength(partial, partial.length, 0));
  }

  @Test
  void resumesScanningAcrossAChunkBoundary() {
    byte[] whole = ascii("From: a@b\r\nSubject: x\r\n\r\nbody");
    int firstChunk = 22;
    assertEquals(-1, EmailSniffer.headerBlockLength(whole, firstChunk, 0));
    int resume = EmailSniffer.rescanFrom(firstChunk);
    assertTrue(resume <= firstChunk);
    assertEquals(23, EmailSniffer.headerBlockLength(whole, whole.length, resume),
        "a terminator straddling the boundary is still found");
  }

  @Test
  void acceptsBlocksWithAKnownMailField() {
    byte[] block = ascii("Received: from x\r\nX-Custom: y\r\n");
    assertTrue(EmailSniffer.looksLikeHeaderBlock(block, block.length));
  }

  @Test
  void rejectsColonShapedTextWithNoMailField() {
    byte[] block = ascii("key: value\r\nother: thing\r\n");
    assertFalse(EmailSniffer.looksLikeHeaderBlock(block, block.length));
  }

  @Test
  void rejectsBlocksThatDoNotStartWithAField() {
    assertFalse(EmailSniffer.looksLikeHeaderBlock(ascii("just prose\r\nFrom: a@b\r\n"), 23));
    assertFalse(EmailSniffer.looksLikeHeaderBlock(ascii("  From: a@b\r\n"), 13),
        "a continuation line cannot open a header block");
    assertFalse(EmailSniffer.looksLikeHeaderBlock(new byte[0], 0));
  }

  @Test
  void acceptsFoldedContinuationLines() {
    byte[] block = ascii("Subject: a very long\r\n  folded subject\r\nTo: a@b\r\n");
    assertTrue(EmailSniffer.looksLikeHeaderBlock(block, block.length));
  }
}
