package ai.pipestream.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.email.v1.BodyPart;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A .msg body that exists and cannot be read is a failure the caller hears
 * about under its own name. It used to be reported as a body that was never
 * there, or not reported at all.
 */
class MsgBodyReadFailureUnitTest {

  @Test
  void damagedRtfBody_isReportedAsUnreadable_notAsAbsent() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.damagedRtfOnly());

    assertEquals(List.of(), parsed.bodies);
    assertEquals(1, parsed.warnings.size(), parsed.warnings.toString());
    String warning = parsed.warnings.getFirst();
    assertTrue(warning.contains("PidTagRtfCompressed"), warning);
    assertTrue(warning.contains("could not be read"), warning);
    assertFalse(warning.contains("carries no"), "the message does carry a body: " + warning);
  }

  @Test
  void damagedRtfBody_namesWhatWentWrong() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.damagedRtfOnly());

    // POI's own words for the damage, so an operator can tell a bad
    // signature from a cut stream.
    assertTrue(parsed.warnings.getFirst().contains("signature"), parsed.warnings.getFirst());
  }

  @Test
  void damagedRtfBody_besideAPlainBody_costsNothing() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.damagedRtf(MsgFixtures.PLAIN_BODY));

    // RTF is the last resort; with a plain body it is never read, so its
    // damage loses nothing and is not worth a warning.
    assertEquals(List.of("body:plain"), parsed.bodies.stream().map(BodyPart::getPartId).toList());
    assertEquals(List.of(), parsed.warnings);
  }

  @Test
  void messageWithNoBodyAtAll_stillSaysSo() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.noBody());

    assertEquals(List.of(), parsed.bodies);
    assertEquals(List.of("message carries no plain, HTML, or RTF body"), parsed.warnings);
  }

  @Test
  void readableBodies_carryNoWarning() throws Exception {
    RecordingSink parsed = RecordingSink.msg(MsgFixtures.full());

    assertEquals(
        List.of("body:plain", "body:html"),
        parsed.bodies.stream().map(BodyPart::getPartId).toList());
    assertEquals(List.of(), parsed.warnings);
  }
}
