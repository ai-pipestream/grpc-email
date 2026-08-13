package ai.pipestream.email.parse;

/**
 * Per-parse knobs, already reconciled against the server's own limits. The
 * wire options are advisory requests; this is what the parser actually does.
 *
 * @param documentId caller identifier echoed back in EmailInfo
 * @param listAttachments emit an Attachment event per attachment
 * @param includeAttachmentBytes populate Attachment.data (implies listing)
 * @param maxAttachmentBytes per-attachment payload cap; larger payloads are
 *     described without their bytes rather than failing the parse
 */
public record ParseOptions(
    String documentId,
    boolean listAttachments,
    boolean includeAttachmentBytes,
    long maxAttachmentBytes) {

  /** Whether an Attachment event should reach the wire at all. */
  public boolean emitAttachments() {
    return listAttachments || includeAttachmentBytes;
  }
}
