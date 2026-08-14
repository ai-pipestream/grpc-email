package ai.pipestream.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import ai.pipestream.document.v1.BaseTextItem;
import ai.pipestream.document.v1.ContentLayer;
import ai.pipestream.document.v1.DocItemLabel;
import ai.pipestream.document.v1.Document;
import ai.pipestream.document.v1.GroupItem;
import ai.pipestream.document.v1.GroupLabel;
import ai.pipestream.document.v1.PictureItem;
import ai.pipestream.document.v1.RefItem;
import ai.pipestream.document.v1.SourceType;
import ai.pipestream.document.v1.TextItem;
import ai.pipestream.document.v1.TextItemBase;
import ai.pipestream.document.v1.TitleItem;
import ai.pipestream.email.document.EmailDocumentFold;
import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import ai.pipestream.email.v1.ParseEmailResponse;
import ai.pipestream.email.v1.ParseStatus;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fold, driven by synthesized event streams rather than by a parse, so
 * every assertion is about the projection and nothing about MIME.
 *
 * <p>Every test that builds a document also asserts it is structurally sound.
 * The downstream merge renumbers refs additively and does not validate, so a
 * fragment with a dangling child or an unlisted parent loses items silently
 * rather than failing; the integrity check is where that gets caught.
 */
class EmailDocumentFoldUnitTest {

  private static final String VERSION = "9.9.9-test";

  // --- event builders -----------------------------------------------------

  private static ParseEmailResponse event(EmailInfo info) {
    return ParseEmailResponse.newBuilder().setEmailInfo(info).build();
  }

  private static ParseEmailResponse event(BodyPart part) {
    return ParseEmailResponse.newBuilder().setBodyPart(part).build();
  }

  private static ParseEmailResponse event(Attachment attachment) {
    return ParseEmailResponse.newBuilder().setAttachment(attachment).build();
  }

  private static ParseEmailResponse status() {
    return ParseEmailResponse.newBuilder()
        .setStatus(ParseStatus.newBuilder().setState(ParseStatus.State.STATE_OK).setBodyParts(1))
        .build();
  }

  private static Address address(AddressRole role, String name, String email) {
    return Address.newBuilder().setRole(role).setName(name).setAddress(email).build();
  }

  private static EmailInfo envelope() {
    return EmailInfo.newBuilder()
        .setDocumentId("doc-1")
        .setFormat(EmailFormat.EMAIL_FORMAT_EML)
        .setSubject("Docket 24-1183 scheduling order")
        .addAddresses(address(AddressRole.ADDRESS_ROLE_FROM, "Clerk of Court", "clerk@example.gov"))
        .addAddresses(address(AddressRole.ADDRESS_ROLE_TO, "Ada Counsel", "ada@example.com"))
        .addAddresses(address(AddressRole.ADDRESS_ROLE_TO, "", "bob@example.com"))
        .addAddresses(address(AddressRole.ADDRESS_ROLE_CC, "", "cc@example.com"))
        .setDate(Timestamp.newBuilder().setSeconds(1_700_000_000L))
        .setReceivedDate(Timestamp.newBuilder().setSeconds(1_700_000_060L))
        .setMessageId("eml-0001@example.gov")
        .setInReplyTo("parent-0000@example.com")
        .addReferences("root-0000@example.com")
        .addReferences("parent-0000@example.com")
        .setContentType("multipart/mixed; boundary=abc")
        .build();
  }

  private static BodyPart plain(String partId, String text) {
    return BodyPart.newBuilder()
        .setPartId(partId)
        .setMediaType(BodyMediaType.BODY_MEDIA_TYPE_PLAIN)
        .setContentTypeRaw("text/plain")
        .setText(text)
        .build();
  }

  private static BodyPart html(String partId, String markup) {
    return BodyPart.newBuilder()
        .setPartId(partId)
        .setMediaType(BodyMediaType.BODY_MEDIA_TYPE_HTML)
        .setContentTypeRaw("text/html")
        .setText(markup)
        .build();
  }

  private static Attachment attachment(
      int index, String partId, String filename, String contentType, long size) {
    return Attachment.newBuilder()
        .setIndex(index)
        .setPartId(partId)
        .setFilename(filename)
        .setContentType(contentType)
        .setSizeBytes(size)
        .build();
  }

  private static Attachment inlineImage(int index, String partId, String contentId) {
    return attachment(index, partId, "seal.png", "image/png", 8).toBuilder()
        .setInline(true)
        .setContentId(contentId)
        .build();
  }

  /** A full stream: envelope, plain body, HTML body, two attachments, status. */
  private static Document foldFullStream() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope()));
    fold.consume(event(plain("1.1.1", "First paragraph.\n\nSecond paragraph.")));
    fold.consume(event(html("1.1.2", "<html><body><p>First paragraph.</p></body></html>")));
    fold.consume(event(attachment(0, "1.2", "order.pdf", "application/pdf", 25)));
    fold.consume(event(inlineImage(1, "1.3", "seal@example.gov")));
    fold.consume(status());
    return fold.take();
  }

  // --- accessors ----------------------------------------------------------

  private static TextItemBase base(BaseTextItem item) {
    return switch (item.getItemCase()) {
      case TITLE -> item.getTitle().getBase();
      case LIST_ITEM -> item.getListItem().getBase();
      case TEXT -> item.getText().getBase();
      default -> throw new AssertionError("unexpected text variant " + item.getItemCase());
    };
  }

  private static Map<String, Value> bodyFields(Document document) {
    return document.getBody().getMeta().getCustomFieldsMap();
  }

  private static List<String> strings(Value value) {
    return value.getListValue().getValuesList().stream().map(Value::getStringValue).toList();
  }

  private static List<String> childRefs(GroupItem group) {
    return group.getChildrenList().stream().map(RefItem::getRef).toList();
  }

  // --- structure ----------------------------------------------------------

  @Test
  @DisplayName("a folded stream is structurally sound")
  void foldedStreamPassesTheIntegrityCheck() {
    assertThat(EmailDocumentFold.integrityErrors(foldFullStream())).isEmpty();
  }

  @Test
  void everyItemIsLinkedBothWaysAndNumberedDensely() {
    Document document = foldFullStream();

    assertThat(document.getSchemaName()).isEqualTo("docling_document_v2");
    assertThat(document.getBody().getSelfRef()).isEqualTo("#/body");
    assertThat(document.getFurniture().getSelfRef()).isEqualTo("#/furniture");
    assertThat(document.getFurniture().getChildrenList()).isEmpty();

    for (int index = 0; index < document.getTextsCount(); index++) {
      assertThat(base(document.getTexts(index)).getSelfRef()).isEqualTo("#/texts/" + index);
    }
    for (int index = 0; index < document.getPicturesCount(); index++) {
      assertThat(document.getPictures(index).getSelfRef()).isEqualTo("#/pictures/" + index);
    }
    for (int index = 0; index < document.getGroupsCount(); index++) {
      assertThat(document.getGroups(index).getSelfRef()).isEqualTo("#/groups/" + index);
    }

    assertThat(childRefs(document.getBody()))
        .containsExactly("#/texts/0", "#/texts/1", "#/texts/2", "#/groups/0", "#/pictures/0");
  }

  @Test
  @DisplayName("email has no pages, so nothing carries provenance or a form region")
  void nothingInventsProvenance() {
    Document document = foldFullStream();
    assertThat(document.getTextsList()).allSatisfy(item ->
        assertThat(base(item).getProvList()).isEmpty());
    assertThat(document.getPicturesList()).allSatisfy(picture ->
        assertThat(picture.getProvList()).isEmpty());
    assertThat(document.getFieldRegionsList()).isEmpty();
    assertThat(document.getFieldItemsList()).isEmpty();
    assertThat(document.getPagesMap()).isEmpty();
    assertThat(document.getTablesList()).isEmpty();
  }

  // --- envelope -----------------------------------------------------------

  @Test
  void theEnvelopeBecomesTheNameTheOriginAndTypedKeyValues() {
    Document document = foldFullStream();

    assertThat(document.getName()).isEqualTo("Docket 24-1183 scheduling order");
    assertThat(document.getOrigin().getMimetype()).isEqualTo("message/rfc822");
    assertThat(document.getOrigin().getFilename()).isEqualTo("doc-1");

    Map<String, Value> fields = bodyFields(document);
    assertThat(strings(fields.get("email.from"))).containsExactly(
        "Clerk of Court <clerk@example.gov>");
    assertThat(strings(fields.get("email.to"))).containsExactly(
        "Ada Counsel <ada@example.com>", "bob@example.com");
    assertThat(strings(fields.get("email.cc"))).containsExactly("cc@example.com");
    assertThat(fields).doesNotContainKey("email.bcc");
    assertThat(fields.get("email.date").getStringValue()).isEqualTo("2023-11-14T22:13:20Z");
    assertThat(fields.get("email.received_date").getStringValue())
        .isEqualTo("2023-11-14T22:14:20Z");
    assertThat(fields.get("email.message_id").getStringValue()).isEqualTo("eml-0001@example.gov");
    assertThat(fields.get("email.in_reply_to").getStringValue())
        .isEqualTo("parent-0000@example.com");
    assertThat(strings(fields.get("email.references")))
        .containsExactly("root-0000@example.com", "parent-0000@example.com");
    assertThat(fields.get("email.content_type").getStringValue())
        .isEqualTo("multipart/mixed; boundary=abc");
  }

  @Test
  @DisplayName("the header list is not dumped into the document")
  void rawHeadersStayOnTheTypedStream() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope().toBuilder()
        .addHeaders(ai.pipestream.email.v1.Header.newBuilder()
            .setName("X-Spam-Status").setValue("No, score=0.1"))
        .build()));
    Document document = fold.take();
    assertThat(bodyFields(document).keySet()).allSatisfy(key ->
        assertThat(key).startsWith("email."));
    assertThat(bodyFields(document)).doesNotContainKey("email.headers");
    assertThat(document.toString()).doesNotContain("X-Spam-Status");
  }

  @Test
  void anAbsentFactIsAnAbsentKeyNotAnEmptyString() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(EmailInfo.newBuilder()
        .setDocumentId("bare")
        .setFormat(EmailFormat.EMAIL_FORMAT_MSG)
        .build()));
    Document document = fold.take();

    assertThat(bodyFields(document)).isEmpty();
    assertThat(document.getName()).isEqualTo("bare");
    assertThat(document.getOrigin().getMimetype()).isEqualTo("application/vnd.ms-outlook");
    assertThat(document.getTextsList()).isEmpty();
    assertThat(EmailDocumentFold.integrityErrors(document)).isEmpty();
  }

  @Test
  void theSubjectIsAlsoTheFirstBodyChildAsATitle() {
    Document document = foldFullStream();
    BaseTextItem first = document.getTexts(0);

    assertThat(childRefs(document.getBody()).get(0)).isEqualTo("#/texts/0");
    assertThat(first.hasTitle()).isTrue();
    assertThat(base(first).getLabel()).isEqualTo(DocItemLabel.DOC_ITEM_LABEL_TITLE);
    assertThat(base(first).getText()).isEqualTo("Docket 24-1183 scheduling order");
    assertThat(base(first).getOrig()).isEqualTo("Docket 24-1183 scheduling order");
    assertThat(base(first).getContentLayer()).isEqualTo(ContentLayer.CONTENT_LAYER_BODY);
    assertThat(base(first).getParent().getRef()).isEqualTo("#/body");
  }

  // --- bodies -------------------------------------------------------------

  @Test
  void eachPlainParagraphIsItsOwnTextItemTaggedWithItsPart() {
    Document document = foldFullStream();
    List<BaseTextItem> texts = document.getTextsList();

    assertThat(texts.get(1).hasText()).isTrue();
    assertThat(base(texts.get(1)).getLabel()).isEqualTo(DocItemLabel.DOC_ITEM_LABEL_TEXT);
    assertThat(base(texts.get(1)).getText()).isEqualTo("First paragraph.");
    assertThat(base(texts.get(2)).getText()).isEqualTo("Second paragraph.");
    assertThat(base(texts.get(1)).getMeta().getCustomFieldsMap().get("email.part_id")
        .getStringValue()).isEqualTo("1.1.1");
    assertThat(base(texts.get(2)).getMeta().getCustomFieldsMap().get("email.part_id")
        .getStringValue()).isEqualTo("1.1.1");
  }

  @Test
  @DisplayName("HTML is the HTML collector's job, so it produces no items here")
  void htmlBodiesAreNotMapped() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope()));
    fold.consume(event(html("1.2", "<html><body><h1>Never parsed</h1></body></html>")));
    Document document = fold.take();

    assertThat(document.getTextsCount()).isEqualTo(1);
    assertThat(document.getTexts(0).hasTitle()).isTrue();
    assertThat(document.toString()).doesNotContain("Never parsed");
  }

  @Test
  void paragraphSplittingIsDeterministicAcrossLineEndingsAndBlankRuns() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(plain("1", "  One.  \r\n\r\n\r\nTwo.\n \nThree.\n\n")));
    List<String> texts =
        fold.take().getTextsList().stream().map(item -> base(item).getText()).toList();
    assertThat(texts).containsExactly("One.", "Two.", "Three.");
  }

  // --- attachments --------------------------------------------------------

  @Test
  void listedAttachmentsBecomeOneListGroupOfDescriptionLines() {
    Document document = foldFullStream();
    GroupItem group = document.getGroups(0);

    assertThat(document.getGroupsCount()).isEqualTo(1);
    assertThat(group.getLabel()).isEqualTo(GroupLabel.GROUP_LABEL_LIST);
    assertThat(group.getName()).isEqualTo("attachments");
    assertThat(group.getParent().getRef()).isEqualTo("#/body");
    assertThat(childRefs(group)).containsExactly("#/texts/3", "#/texts/4");

    BaseTextItem line = document.getTexts(3);
    assertThat(line.hasListItem()).isTrue();
    assertThat(line.getListItem().getEnumerated()).isFalse();
    assertThat(base(line).getLabel()).isEqualTo(DocItemLabel.DOC_ITEM_LABEL_LIST_ITEM);
    assertThat(base(line).getText()).isEqualTo("order.pdf (application/pdf, 25 bytes)");
    assertThat(base(line).getMeta().getCustomFieldsMap().get("email.part_id").getStringValue())
        .isEqualTo("1.2");
    assertThat(base(document.getTexts(4)).getText()).isEqualTo("seal.png (image/png, 8 bytes)");
  }

  @Test
  void aMessageWithNoAttachmentEventsHasNoAttachmentsGroup() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope()));
    fold.consume(event(plain("1", "Body.")));
    fold.consume(status());
    Document document = fold.take();

    assertThat(document.getGroupsList()).isEmpty();
    assertThat(childRefs(document.getBody())).containsExactly("#/texts/0", "#/texts/1");
  }

  @Test
  void anUnnamedUntypedAttachmentIsDescribedWithoutInventingEither() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(attachment(0, "1.2", "", "", 12)));
    Document document = fold.take();
    assertThat(base(document.getTexts(0)).getText()).isEqualTo("(unnamed) (unknown type, 12 bytes)");
  }

  @Test
  void anInlineImageAlsoBecomesAPicturePointingAtTheTypedStream() {
    Document document = foldFullStream();
    PictureItem picture = document.getPictures(0);

    assertThat(document.getPicturesCount()).isEqualTo(1);
    assertThat(picture.getLabel()).isEqualTo(DocItemLabel.DOC_ITEM_LABEL_PICTURE);
    assertThat(picture.getParent().getRef()).isEqualTo("#/body");
    assertThat(picture.getImage().getMimetype()).isEqualTo("image/png");
    assertThat(picture.getImage().getUri()).isEqualTo("part:1.3");
    assertThat(picture.getImage().getUri()).doesNotStartWith("data:");
    assertThat(picture.getImage().getSize().getWidth()).isZero();
    assertThat(picture.getImage().getSize().getHeight()).isZero();
    assertThat(picture.getMeta().getCustomFieldsMap().get("email.content_id").getStringValue())
        .isEqualTo("seal@example.gov");
    assertThat(picture.getMeta().getCustomFieldsMap().get("email.part_id").getStringValue())
        .isEqualTo("1.3");
  }

  @Test
  @DisplayName("only an inline image with a content id is picture-worthy")
  void otherAttachmentsStayListingLines() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(attachment(0, "1.2", "order.pdf", "application/pdf", 25)));
    fold.consume(event(attachment(1, "1.3", "logo.png", "image/png", 9).toBuilder()
        .setContentId("logo@example.gov").build()));
    fold.consume(event(attachment(2, "1.4", "note.txt", "text/plain", 4).toBuilder()
        .setInline(true).setContentId("note@example.gov").build()));
    fold.consume(event(attachment(3, "1.5", "chart.png", "image/png", 9).toBuilder()
        .setInline(true).build()));
    Document document = fold.take();

    assertThat(document.getPicturesList()).isEmpty();
    assertThat(document.getTextsCount()).isEqualTo(4);
    assertThat(EmailDocumentFold.integrityErrors(document)).isEmpty();
  }

  // --- attribution --------------------------------------------------------

  @Test
  void everyItemIsStampedWithTheCollectorTheDialectAndTheBuild() {
    Document document = foldFullStream();
    List<SourceType> sources = document.getTextsList().stream()
        .map(item -> base(item).getSourceList())
        .peek(list -> assertThat(list).hasSize(1))
        .map(List::getFirst)
        .toList();

    assertThat(sources).isNotEmpty().allSatisfy(source -> {
      assertThat(source.hasCollector()).isTrue();
      assertThat(source.getCollector().getCollector()).isEqualTo("email");
      assertThat(source.getCollector().getModel()).isEqualTo("eml");
      assertThat(source.getCollector().getVersion()).isEqualTo(VERSION);
      assertThat(source.getCollector().hasConfidence())
          .as("a declarative mapping is deterministic; a confidence would be noise")
          .isFalse();
    });
    assertThat(document.getPictures(0).getSourceList()).hasSize(1);
    assertThat(document.getPictures(0).getSource(0).getCollector().getModel()).isEqualTo("eml");
  }

  @Test
  void theOutlookDialectIsStampedAsMsg() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope().toBuilder().setFormat(EmailFormat.EMAIL_FORMAT_MSG).build()));
    fold.consume(event(plain("1", "Body.")));
    Document document = fold.take();
    assertThat(base(document.getTexts(1)).getSource(0).getCollector().getModel()).isEqualTo("msg");
  }

  // --- lifecycle ----------------------------------------------------------

  @Test
  void theTrailerAddsNoStructureButIsAccepted() {
    EmailDocumentFold withStatus = new EmailDocumentFold(VERSION);
    withStatus.consume(event(envelope()));
    withStatus.consume(status());

    EmailDocumentFold without = new EmailDocumentFold(VERSION);
    without.consume(event(envelope()));

    assertThat(withStatus.take()).isEqualTo(without.take());
  }

  @Test
  void theFoldIsOneShotAndNeverEatsItsOwnOutput() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope()));
    assertThatIllegalArgumentException().isThrownBy(() -> fold.consume(
        ParseEmailResponse.newBuilder().setDocument(Document.getDefaultInstance()).build()));
    fold.take();
    assertThatIllegalStateException().isThrownBy(fold::take);
    assertThatIllegalStateException().isThrownBy(() -> fold.consume(status()));
  }

  @Test
  void aSecondEnvelopeIsIgnoredRatherThanDuplicatingTheTitle() {
    EmailDocumentFold fold = new EmailDocumentFold(VERSION);
    fold.consume(event(envelope()));
    fold.consume(event(envelope().toBuilder().setSubject("Different").build()));
    Document document = fold.take();

    assertThat(document.getTextsCount()).isEqualTo(1);
    assertThat(document.getName()).isEqualTo("Docket 24-1183 scheduling order");
  }

  // --- the integrity check itself ----------------------------------------

  @Test
  @DisplayName("the integrity check catches what the merge would swallow")
  void integrityCheckFindsBrokenLinks() {
    Document.Builder duplicate = foldFullStream().toBuilder();
    duplicate.getTextsBuilder(2).getTextBuilder().getBaseBuilder().setSelfRef("#/texts/1");
    assertThat(EmailDocumentFold.integrityErrors(duplicate.build()))
        .anyMatch(error -> error.contains("duplicate self_ref #/texts/1"));

    Document.Builder dangling = foldFullStream().toBuilder();
    dangling.getBodyBuilder().addChildren(RefItem.newBuilder().setRef("#/texts/99"));
    assertThat(EmailDocumentFold.integrityErrors(dangling.build()))
        .anyMatch(error -> error.contains("child #/texts/99 of #/body does not resolve"));

    Document.Builder orphan = foldFullStream().toBuilder();
    orphan.getBodyBuilder().clearChildren();
    assertThat(EmailDocumentFold.integrityErrors(orphan.build()))
        .anyMatch(error -> error.contains("#/body does not list #/texts/0 as a child"));

    Document.Builder unparented = foldFullStream().toBuilder();
    unparented.getTextsBuilder(1).getTextBuilder().getBaseBuilder()
        .setParent(RefItem.newBuilder().setRef("#/groups/7"));
    assertThat(EmailDocumentFold.integrityErrors(unparented.build()))
        .anyMatch(error -> error.contains("parent #/groups/7 of #/texts/1 does not resolve"));

    Document empty = Document.newBuilder()
        .addTexts(BaseTextItem.newBuilder().setText(TextItem.newBuilder()
            .setBase(TextItemBase.newBuilder().setText("no self ref"))))
        .build();
    assertThat(EmailDocumentFold.integrityErrors(empty))
        .containsExactly("item with empty self_ref");

    Document unsetVariant = Document.newBuilder()
        .addTexts(BaseTextItem.getDefaultInstance())
        .build();
    assertThat(EmailDocumentFold.integrityErrors(unsetVariant))
        .containsExactly("text item with unset variant");
  }

  @Test
  void anEmptyDocumentIsSound() {
    assertThat(EmailDocumentFold.integrityErrors(new EmailDocumentFold(VERSION).take())).isEmpty();
    assertThat(EmailDocumentFold.integrityErrors(Document.newBuilder()
        .addTexts(BaseTextItem.newBuilder().setTitle(TitleItem.newBuilder()
            .setBase(TextItemBase.newBuilder().setSelfRef("#/texts/0").setText("orphan"))))
        .build()))
        .as("an item with no parent at all is legal; the merge hangs it where it lands")
        .isEmpty();
  }
}
