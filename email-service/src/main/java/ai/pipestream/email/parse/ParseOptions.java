package ai.pipestream.email.parse;

/**
 * Per-parse knobs, already reconciled against the server's own limits. The
 * wire options are advisory requests; this is what the parser actually does.
 *
 * <p>{@code listAttachments} is the resolved answer, not the raw wire bool:
 * the server folds {@code omit_attachment_list}, {@code list_attachments} and
 * {@code include_attachment_bytes} into one decision before building this
 * record, so nothing downstream has to know the polarity of any of them.
 *
 * @param documentId caller identifier echoed back in EmailInfo
 * @param filename advisory source filename from the caller, recorded as
 *     DocumentOrigin.filename and never used for format detection
 * @param contentType advisory content type from the caller, recorded but
 *     never trusted; the container format comes from the bytes
 * @param listAttachments emit an Attachment event per attachment
 * @param includeAttachmentBytes populate Attachment.data (implies listing)
 * @param maxAttachmentBytes per-attachment payload cap; larger payloads are
 *     described without their bytes rather than failing the parse
 */
public record ParseOptions(
    String documentId,
    String filename,
    String contentType,
    boolean listAttachments,
    boolean includeAttachmentBytes,
    long maxAttachmentBytes) {

  /** Whether an Attachment event should reach the wire at all. */
  public boolean emitAttachments() {
    return listAttachments || includeAttachmentBytes;
  }
}
