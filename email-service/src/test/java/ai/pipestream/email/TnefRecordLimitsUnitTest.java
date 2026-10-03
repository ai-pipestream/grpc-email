package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;

import ai.pipestream.email.parse.TnefRecordLimits;
import org.apache.poi.hmef.attribute.MAPIAttribute;
import org.apache.poi.hmef.attribute.TNEFAttribute;
import org.junit.jupiter.api.Test;

/** POI's TNEF ceilings follow the upload cap, within what a Java array can hold. */
class TnefRecordLimitsUnitTest {

  @Test
  void ceilingsFollowTheUploadCapWithinArrayBounds() {
    int before = MAPIAttribute.getMaxRecordLength();
    try {
      assertThat(TnefRecordLimits.applyUploadCap(64L * 1024 * 1024)).isEqualTo(64 * 1024 * 1024);
      assertThat(MAPIAttribute.getMaxRecordLength()).isEqualTo(64 * 1024 * 1024);
      assertThat(TNEFAttribute.getMaxRecordLength()).isEqualTo(64 * 1024 * 1024);

      assertThat(TnefRecordLimits.applyUploadCap(Long.MAX_VALUE))
          .as("a cap past any array length is held to the largest one")
          .isEqualTo(Integer.MAX_VALUE - 8);
      assertThat(TnefRecordLimits.applyUploadCap(0))
          .as("a ceiling is never zero")
          .isEqualTo(1);
    } finally {
      TnefRecordLimits.applyUploadCap(before);
    }
  }
}
