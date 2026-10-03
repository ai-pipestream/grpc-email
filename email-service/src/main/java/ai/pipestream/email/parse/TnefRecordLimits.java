package ai.pipestream.email.parse;

import org.apache.poi.hmef.attribute.MAPIAttribute;
import org.apache.poi.hmef.attribute.TNEFAttribute;

/**
 * The ceilings Apache POI's HMEF puts on what one TNEF record, and one MAPI
 * property inside it, may claim before POI allocates for it. POI's defaults
 * are 20 MB a record and 1 MB a property, and the property ceiling is too
 * low for real mail: an HTML or RTF body over 1 MB makes POI refuse the
 * whole property list it sits in, so every body in that list is lost.
 *
 * <p>Both ceilings are raised to the service's own upload cap instead. Nothing
 * inside a container can legitimately be larger than the message that carried
 * it, so a real body always fits, while a length field claiming more than any
 * accepted upload is still refused before anything is allocated. A claim
 * under the cap that the record cannot back fails on the read that follows,
 * after one allocation no larger than an upload the service already holds.
 *
 * <p>POI keeps these ceilings in static fields, so they are process-wide: the
 * last call wins, and a server process builds one service.
 */
public final class TnefRecordLimits {

  /** The largest array length every JVM allocates. */
  private static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

  private TnefRecordLimits() {}

  /**
   * Sets the record and property ceilings to {@code maxDocumentBytes}.
   *
   * @param maxDocumentBytes the largest message the service accepts
   * @return the ceiling now in force, in bytes
   */
  public static synchronized int applyUploadCap(long maxDocumentBytes) {
    int ceiling = (int) Math.max(1, Math.min(MAX_ARRAY_LENGTH, maxDocumentBytes));
    MAPIAttribute.setMaxRecordLength(ceiling);
    TNEFAttribute.setMaxRecordLength(ceiling);
    return ceiling;
  }
}
