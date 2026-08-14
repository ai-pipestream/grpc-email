package ai.pipestream.email.document;

import ai.pipestream.document.v1.BaseTextItem;
import ai.pipestream.document.v1.CollectorSource;
import ai.pipestream.document.v1.ContentLayer;
import ai.pipestream.document.v1.DocItemLabel;
import ai.pipestream.document.v1.Document;
import ai.pipestream.document.v1.DocumentOrigin;
import ai.pipestream.document.v1.GroupItem;
import ai.pipestream.document.v1.GroupLabel;
import ai.pipestream.document.v1.ImageRef;
import ai.pipestream.document.v1.ListItem;
import ai.pipestream.document.v1.PictureItem;
import ai.pipestream.document.v1.RefItem;
import ai.pipestream.document.v1.SourceType;
import ai.pipestream.document.v1.TextItem;
import ai.pipestream.document.v1.TextItemBase;
import ai.pipestream.document.v1.TitleItem;
import ai.pipestream.email.v1.Address;
import ai.pipestream.email.v1.AddressRole;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyMediaType;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import ai.pipestream.email.v1.ParseEmailResponse;
import com.google.protobuf.ListValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Folds this collector's own event stream into one
 * {@link ai.pipestream.document.v1.Document}.
 *
 * <p>The typed events are the lossless wire; the Document is the lossy
 * structural projection the whole collector fleet shares, so a client on the
 * Document plane does not reimplement the email mapping. The fold is fed the
 * very messages the server writes -- {@link #consume(ParseEmailResponse)} per
 * outbound event, then {@link #take()} once -- so it can never disagree with
 * the stream it projects: there is no second parse and no second traversal.
 * It is single-pass and O(1) per event.
 *
 * <p>What is mapped, and what deliberately is not:
 *
 * <ul>
 *   <li>The envelope becomes the document name, its origin, and typed
 *       key/values in the body group's {@code meta.custom_fields} under
 *       {@code email.*} keys. Only envelope facts go there; the lossless
 *       header list stays on {@link EmailInfo}, because a Document is not a
 *       place to dump headers.
 *   <li>The subject additionally becomes a {@link TitleItem}, the first body
 *       child.
 *   <li>Each {@code text/plain} body part becomes one {@link TextItem} per
 *       blank-line-separated paragraph.
 *   <li>HTML body parts are not parsed and not mapped. Parsing HTML is the
 *       HTML collector's job; its items merge into this fragment downstream.
 *   <li>Attachment events -- which the client only gets when it asked for the
 *       listing -- become a {@link GroupLabel#GROUP_LABEL_LIST} group named
 *       "attachments"; an inline image additionally becomes a
 *       {@link PictureItem} whose {@link ImageRef} points at the typed
 *       stream with a {@code part:<part_id>} URI rather than embedding bytes.
 *   <li>No provenance. Email has no pages and no boxes, so {@code prov} stays
 *       empty and source locators ride in per-item {@code custom_fields}
 *       instead of an invented page number.
 *   <li>No {@code field_regions} / {@code field_items}: the downstream
 *       additive merge does not renumber those and would silently drop them.
 * </ul>
 *
 * <p>The fragment is self-contained and densely numbered from zero, which is
 * what the coordinator's additive merge expects; {@link #integrityErrors} is
 * the assertion that it stayed that way.
 *
 * <p>Not thread-safe. One fold per RPC, called from the same places that
 * write to that RPC's stream.
 */
public final class EmailDocumentFold {

  /** Collector name stamped on every item this fold creates. */
  public static final String COLLECTOR = "email";

  /** Schema identifier every collector in the fleet writes. */
  public static final String SCHEMA_NAME = "docling_document_v2";

  /**
   * URI scheme of an {@link ImageRef} produced by this fold. The rest of the
   * URI is the {@code part_id} of the Attachment event carrying the bytes, so
   * a client resolves an image by looking at the stream it already has --
   * "part:1.3" for an .eml MIME path, "part:attach:1" for a MAPI storage.
   * Bytes are never embedded: the Document is one gRPC message.
   */
  public static final String PART_URI_SCHEME = "part:";

  /** Media type of the .eml container, for {@link DocumentOrigin}. */
  public static final String EML_MIMETYPE = "message/rfc822";

  /** Media type of the .msg container, for {@link DocumentOrigin}. */
  public static final String MSG_MIMETYPE = "application/vnd.ms-outlook";

  private static final String BODY_REF = "#/body";
  private static final String FURNITURE_REF = "#/furniture";
  private static final String GROUPS_PREFIX = "#/groups/";
  private static final String ATTACHMENTS_GROUP_NAME = "attachments";
  private static final String KEY_PART_ID = "email.part_id";
  private static final String KEY_CONTENT_ID = "email.content_id";

  private final String version;
  private final Document.Builder document = Document.newBuilder();

  private boolean envelopeSeen;
  private boolean taken;
  private String model = "";
  private String attachmentsGroupRef;

  /**
   * Creates a fold for one parse.
   *
   * @param serviceVersion this server's own version string, stamped as
   *     {@link CollectorSource#getVersion()} on every item so a downstream
   *     merge can tell which build produced which fragment
   */
  public EmailDocumentFold(String serviceVersion) {
    this.version = serviceVersion == null ? "" : serviceVersion;
    document.setSchemaName(SCHEMA_NAME);
    document.getBodyBuilder().setSelfRef(BODY_REF).setContentLayer(ContentLayer.CONTENT_LAYER_BODY);
    document.getFurnitureBuilder()
        .setSelfRef(FURNITURE_REF)
        .setContentLayer(ContentLayer.CONTENT_LAYER_FURNITURE);
  }

  /**
   * Folds one outbound event. Call this with every response the server writes
   * to the wire, in wire order, including the ParseStatus trailer.
   *
   * <p>The trailer contributes no items -- counts and warnings belong to the
   * typed stream, not to a document's structure -- but it is accepted so the
   * fold sees the whole stream and so the server has one uniform emit path.
   *
   * @param event a response message the server is about to send
   * @throws IllegalStateException if the document has already been taken
   * @throws IllegalArgumentException if handed a document event; the fold's
   *     own output is never one of its inputs
   */
  public void consume(ParseEmailResponse event) {
    if (taken) {
      throw new IllegalStateException("the document was already taken; the fold is finished");
    }
    switch (event.getEventCase()) {
      case EMAIL_INFO -> envelope(event.getEmailInfo());
      case BODY_PART -> bodyPart(event.getBodyPart());
      case ATTACHMENT -> attachment(event.getAttachment());
      case STATUS -> { /* the trailer closes the stream; it adds no structure */ }
      case DOCUMENT -> throw new IllegalArgumentException(
          "a document event is this fold's output, never its input");
      case EVENT_NOT_SET -> { /* nothing was set; nothing to fold */ }
    }
  }

  /**
   * Returns the folded document. Callable once: the fold is a one-shot
   * projection of one RPC, and reusing it across parses would leak items
   * between messages.
   *
   * @return the document built from every event consumed so far
   * @throws IllegalStateException on a second call
   */
  public Document take() {
    if (taken) {
      throw new IllegalStateException("the document was already taken; the fold is finished");
    }
    taken = true;
    return document.build();
  }

  // --- envelope -----------------------------------------------------------

  /**
   * Maps the envelope: the document's name and origin, the typed key/values
   * of the body group, and the title item. Only the first envelope counts;
   * the server emits exactly one.
   */
  private void envelope(EmailInfo info) {
    if (envelopeSeen) {
      return;
    }
    envelopeSeen = true;
    model = model(info.getFormat());

    String name = info.getSubject().isEmpty() ? info.getDocumentId() : info.getSubject();
    document.setName(name);

    String mimetype = mimetype(info.getFormat());
    if (!mimetype.isEmpty() || !info.getDocumentId().isEmpty()) {
      DocumentOrigin.Builder origin = document.getOriginBuilder().setMimetype(mimetype);
      if (!info.getDocumentId().isEmpty()) {
        origin.setFilename(info.getDocumentId());
      }
    }

    Map<String, Value> facts = envelopeFacts(info);
    if (!facts.isEmpty()) {
      document.getBodyBuilder().getMetaBuilder().putAllCustomFields(facts);
    }

    if (!info.getSubject().isEmpty()) {
      addTitle(BODY_REF, info.getSubject());
    }
  }

  /**
   * The envelope as typed key/values rather than a paragraph of prose:
   * addresses grouped by role, the two dates as RFC 3339 strings, the
   * threading ids, and the root content type. Absent facts are absent keys,
   * never empty strings, so a consumer can tell "the message had no Date"
   * from "the date was empty".
   *
   * <p>These land on the body group, whose {@code custom_fields} are
   * first-writer-wins in the coordinator's merge: another collector's
   * fragment for the same document will not overwrite them, and this fold
   * must not count on overwriting anyone either.
   */
  private static Map<String, Value> envelopeFacts(EmailInfo info) {
    Map<String, Value> facts = new LinkedHashMap<>();
    Map<AddressRole, List<Value>> byRole = new LinkedHashMap<>();
    for (Address address : info.getAddressesList()) {
      String rendered = render(address);
      if (!rendered.isEmpty()) {
        byRole.computeIfAbsent(address.getRole(), role -> new ArrayList<>()).add(string(rendered));
      }
    }
    byRole.forEach((role, values) -> {
      String key = key(role);
      if (!key.isEmpty()) {
        facts.put(key, list(values));
      }
    });
    if (info.hasDate()) {
      facts.put("email.date", string(rfc3339(info.getDate())));
    }
    if (info.hasReceivedDate()) {
      facts.put("email.received_date", string(rfc3339(info.getReceivedDate())));
    }
    if (!info.getMessageId().isEmpty()) {
      facts.put("email.message_id", string(info.getMessageId()));
    }
    if (!info.getInReplyTo().isEmpty()) {
      facts.put("email.in_reply_to", string(info.getInReplyTo()));
    }
    if (info.getReferencesCount() > 0) {
      facts.put("email.references",
          list(info.getReferencesList().stream().map(EmailDocumentFold::string).toList()));
    }
    if (!info.getContentType().isEmpty()) {
      facts.put("email.content_type", string(info.getContentType()));
    }
    return facts;
  }

  /** One mailbox as a display string, losslessly recoverable from the events. */
  private static String render(Address address) {
    if (address.getName().isEmpty()) {
      return address.getAddress();
    }
    if (address.getAddress().isEmpty()) {
      return address.getName();
    }
    return address.getName() + " <" + address.getAddress() + ">";
  }

  /** The custom-fields key for a role, or empty for a role we do not name. */
  private static String key(AddressRole role) {
    return switch (role) {
      case ADDRESS_ROLE_FROM -> "email.from";
      case ADDRESS_ROLE_TO -> "email.to";
      case ADDRESS_ROLE_CC -> "email.cc";
      case ADDRESS_ROLE_BCC -> "email.bcc";
      case ADDRESS_ROLE_REPLY_TO -> "email.reply_to";
      case ADDRESS_ROLE_SENDER -> "email.sender";
      case ADDRESS_ROLE_UNSPECIFIED, UNRECOGNIZED -> "";
    };
  }

  /** The wire format as the lowercase dialect name stamped on every item. */
  private static String model(EmailFormat format) {
    return switch (format) {
      case EMAIL_FORMAT_EML -> "eml";
      case EMAIL_FORMAT_MSG -> "msg";
      case EMAIL_FORMAT_UNSPECIFIED, UNRECOGNIZED -> "";
    };
  }

  /** The container media type, or empty when the format is not one we know. */
  private static String mimetype(EmailFormat format) {
    return switch (format) {
      case EMAIL_FORMAT_EML -> EML_MIMETYPE;
      case EMAIL_FORMAT_MSG -> MSG_MIMETYPE;
      case EMAIL_FORMAT_UNSPECIFIED, UNRECOGNIZED -> "";
    };
  }

  private static String rfc3339(Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString();
  }

  // --- bodies -------------------------------------------------------------

  /**
   * Maps a plain-text body part to one text item per paragraph. HTML parts
   * are skipped whole: this collector hands HTML to the HTML collector
   * verbatim on the typed stream and refuses to guess at its structure here.
   */
  private void bodyPart(BodyPart part) {
    if (part.getMediaType() != BodyMediaType.BODY_MEDIA_TYPE_PLAIN) {
      return;
    }
    for (String paragraph : paragraphs(part.getText())) {
      addText(BODY_REF, paragraph, Map.of(KEY_PART_ID, string(part.getPartId())));
    }
  }

  /**
   * Splits body text on blank lines. Deterministic on purpose: line endings
   * are normalized first so the same message chunked differently, or written
   * with CRLF instead of LF, yields the same items; each paragraph is
   * stripped and empty ones are dropped.
   */
  static List<String> paragraphs(String text) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    List<String> paragraphs = new ArrayList<>();
    for (String candidate : normalized.split("\n[ \t]*\n")) {
      String trimmed = candidate.strip();
      if (!trimmed.isEmpty()) {
        paragraphs.add(trimmed);
      }
    }
    return paragraphs;
  }

  // --- attachments --------------------------------------------------------

  /**
   * Maps one attachment event to a line in the attachments list, and -- when
   * it is an inline image an HTML body could reference -- to a picture whose
   * image ref points back at the typed stream.
   */
  private void attachment(Attachment attachment) {
    Map<String, Value> partId = Map.of(KEY_PART_ID, string(attachment.getPartId()));
    addListItem(attachmentsGroup(), describe(attachment), partId);
    if (isInlineImage(attachment)) {
      addPicture(attachment);
    }
  }

  /** Lazily opens the attachments group, so a message without one has none. */
  private String attachmentsGroup() {
    if (attachmentsGroupRef == null) {
      attachmentsGroupRef =
          addGroup(BODY_REF, GroupLabel.GROUP_LABEL_LIST, ATTACHMENTS_GROUP_NAME);
    }
    return attachmentsGroupRef;
  }

  /**
   * The one-line description of an attachment. An unnamed part is written as
   * "(unnamed)" and a part that declared no media type as "unknown type": the
   * listing stays readable without inventing a filename or a MIME type that
   * the message never carried. ParseStatus already warns about unnamed parts.
   */
  private static String describe(Attachment attachment) {
    String filename = attachment.getFilename().isEmpty() ? "(unnamed)" : attachment.getFilename();
    String contentType =
        attachment.getContentType().isEmpty() ? "unknown type" : attachment.getContentType();
    return filename + " (" + contentType + ", " + attachment.getSizeBytes() + " bytes)";
  }

  /**
   * An inline image is a part an HTML body can reference by content id, so it
   * is real document content rather than just a file the message carried.
   * All three conditions must hold; an inline part with no content id is
   * unreferenceable and stays a listing line only.
   */
  private static boolean isInlineImage(Attachment attachment) {
    return attachment.getInline()
        && attachment.getContentType().startsWith("image/")
        && !attachment.getContentId().isEmpty();
  }

  // --- append primitives --------------------------------------------------

  /**
   * Resolves a parent ref to the group that must list the child. Every ref
   * this fold hands in is one it created, so an unresolvable ref is a bug in
   * the fold, not input to defend against.
   */
  private GroupItem.Builder groupByRef(String ref) {
    if (FURNITURE_REF.equals(ref)) {
      return document.getFurnitureBuilder();
    }
    if (ref.startsWith(GROUPS_PREFIX)) {
      return document.getGroupsBuilder(Integer.parseInt(ref.substring(GROUPS_PREFIX.length())));
    }
    return document.getBodyBuilder();
  }

  /** Records the parent-to-child half of the link. Both halves, always. */
  private void linkChild(String parentRef, String childRef) {
    groupByRef(parentRef).addChildren(RefItem.newBuilder().setRef(childRef));
  }

  /**
   * Attributes an item to this collector. Deterministic mapping, so no
   * confidence is claimed: a number there would be noise, and downstream
   * rankers would read it as a signal.
   */
  private SourceType source() {
    CollectorSource.Builder collector =
        CollectorSource.newBuilder().setCollector(COLLECTOR).setVersion(version);
    if (!model.isEmpty()) {
      collector.setModel(model);
    }
    return SourceType.newBuilder().setCollector(collector).build();
  }

  private String addGroup(String parentRef, GroupLabel label, String name) {
    String ref = GROUPS_PREFIX + document.getGroupsCount();
    document.addGroups(GroupItem.newBuilder()
        .setSelfRef(ref)
        .setParent(RefItem.newBuilder().setRef(parentRef))
        .setContentLayer(ContentLayer.CONTENT_LAYER_BODY)
        .setLabel(label)
        .setName(name));
    linkChild(parentRef, ref);
    return ref;
  }

  /** The common shape of every text item: both halves of the link, no prov. */
  private TextItemBase.Builder textBase(
      String selfRef,
      String parentRef,
      DocItemLabel label,
      String text,
      Map<String, Value> customFields) {
    TextItemBase.Builder base = TextItemBase.newBuilder()
        .setSelfRef(selfRef)
        .setParent(RefItem.newBuilder().setRef(parentRef))
        .setContentLayer(ContentLayer.CONTENT_LAYER_BODY)
        .setLabel(label)
        .setOrig(text)
        .setText(text)
        .addSource(source());
    if (!customFields.isEmpty()) {
      base.getMetaBuilder().putAllCustomFields(customFields);
    }
    return base;
  }

  private String addTitle(String parentRef, String text) {
    String ref = "#/texts/" + document.getTextsCount();
    document.addTexts(BaseTextItem.newBuilder().setTitle(TitleItem.newBuilder()
        .setBase(textBase(ref, parentRef, DocItemLabel.DOC_ITEM_LABEL_TITLE, text, Map.of()))));
    linkChild(parentRef, ref);
    return ref;
  }

  private String addText(String parentRef, String text, Map<String, Value> customFields) {
    String ref = "#/texts/" + document.getTextsCount();
    document.addTexts(BaseTextItem.newBuilder().setText(TextItem.newBuilder().setBase(
        textBase(ref, parentRef, DocItemLabel.DOC_ITEM_LABEL_TEXT, text, customFields))));
    linkChild(parentRef, ref);
    return ref;
  }

  private String addListItem(String parentRef, String text, Map<String, Value> customFields) {
    String ref = "#/texts/" + document.getTextsCount();
    document.addTexts(BaseTextItem.newBuilder().setListItem(ListItem.newBuilder()
        .setEnumerated(false)
        .setBase(textBase(
            ref, parentRef, DocItemLabel.DOC_ITEM_LABEL_LIST_ITEM, text, customFields))));
    linkChild(parentRef, ref);
    return ref;
  }

  /**
   * A picture that points at the bytes instead of carrying them. Size stays
   * unset: this collector never decodes the image, and a guessed size is
   * worse than no size.
   */
  private String addPicture(Attachment attachment) {
    String ref = "#/pictures/" + document.getPicturesCount();
    PictureItem.Builder picture = PictureItem.newBuilder()
        .setSelfRef(ref)
        .setParent(RefItem.newBuilder().setRef(BODY_REF))
        .setContentLayer(ContentLayer.CONTENT_LAYER_BODY)
        .setLabel(DocItemLabel.DOC_ITEM_LABEL_PICTURE)
        .setImage(ImageRef.newBuilder()
            .setMimetype(attachment.getContentType())
            .setUri(PART_URI_SCHEME + attachment.getPartId()))
        .addSource(source());
    picture.getMetaBuilder()
        .putCustomFields(KEY_CONTENT_ID, string(attachment.getContentId()))
        .putCustomFields(KEY_PART_ID, string(attachment.getPartId()));
    document.addPictures(picture);
    linkChild(BODY_REF, ref);
    return ref;
  }

  private static Value string(String value) {
    return Value.newBuilder().setStringValue(value).build();
  }

  private static Value list(List<Value> values) {
    return Value.newBuilder()
        .setListValue(ListValue.newBuilder().addAllValues(values))
        .build();
  }

  // --- integrity ----------------------------------------------------------

  /**
   * Checks the structural invariants the downstream additive merge relies on
   * and returns one message per violation, in a stable order. A clean fold
   * returns an empty list, which is what the tests assert.
   *
   * <p>The merge renumbers refs as it splices fragments together, so a
   * fragment that is internally inconsistent -- a duplicate self ref, a child
   * nothing resolves, a parent that does not list its child -- does not fail
   * loudly there, it silently loses items. Checking here is how that never
   * reaches the merge.
   *
   * @param document a folded document fragment
   * @return the violations found, empty when the fragment is sound
   */
  public static List<String> integrityErrors(Document document) {
    List<String> errors = new ArrayList<>();
    Set<String> refs = new HashSet<>(Set.of(BODY_REF, FURNITURE_REF));
    // (child ref, parent ref) pairs and every children list, gathered in one
    // walk so parents can be validated against their children afterwards.
    List<Map.Entry<String, String>> parents = new ArrayList<>();
    Map<String, Set<String>> children = new TreeMap<>();

    children.put(BODY_REF, childRefs(document.getBody().getChildrenList()));
    children.put(FURNITURE_REF, childRefs(document.getFurniture().getChildrenList()));

    for (GroupItem group : document.getGroupsList()) {
      collect(errors, refs, parents, children, group.getSelfRef(), group.getChildrenList(),
          group.hasParent(), group.getParent().getRef());
    }
    for (BaseTextItem item : document.getTextsList()) {
      TextItemBase base = textBase(item);
      if (base == null) {
        errors.add("text item with unset variant");
        continue;
      }
      collect(errors, refs, parents, children, base.getSelfRef(), base.getChildrenList(),
          base.hasParent(), base.getParent().getRef());
    }
    for (PictureItem picture : document.getPicturesList()) {
      collect(errors, refs, parents, children, picture.getSelfRef(), picture.getChildrenList(),
          picture.hasParent(), picture.getParent().getRef());
    }

    children.forEach((parent, listed) -> listed.stream()
        .filter(child -> !refs.contains(child))
        .forEach(child -> errors.add("child " + child + " of " + parent + " does not resolve")));
    for (Map.Entry<String, String> parent : parents) {
      if (!refs.contains(parent.getValue())) {
        errors.add("parent " + parent.getValue() + " of " + parent.getKey()
            + " does not resolve");
        continue;
      }
      if (!children.getOrDefault(parent.getValue(), Set.of()).contains(parent.getKey())) {
        errors.add("parent " + parent.getValue() + " does not list " + parent.getKey()
            + " as a child");
      }
    }
    return errors;
  }

  private static void collect(
      List<String> errors,
      Set<String> refs,
      List<Map.Entry<String, String>> parents,
      Map<String, Set<String>> children,
      String selfRef,
      List<RefItem> childRefs,
      boolean hasParent,
      String parentRef) {
    if (selfRef.isEmpty()) {
      errors.add("item with empty self_ref");
      return;
    }
    if (!refs.add(selfRef)) {
      errors.add("duplicate self_ref " + selfRef);
    }
    if (!childRefs.isEmpty()) {
      children.computeIfAbsent(selfRef, ref -> new LinkedHashSet<>())
          .addAll(childRefs(childRefs));
    }
    if (hasParent) {
      parents.add(Map.entry(selfRef, parentRef));
    }
  }

  private static Set<String> childRefs(List<RefItem> refItems) {
    Set<String> refs = new LinkedHashSet<>();
    for (RefItem item : refItems) {
      refs.add(item.getRef());
    }
    return refs;
  }

  /** The base of whichever text variant is set, or null for an empty union. */
  private static TextItemBase textBase(BaseTextItem item) {
    return switch (item.getItemCase()) {
      case TITLE -> item.getTitle().getBase();
      case SECTION_HEADER -> item.getSectionHeader().getBase();
      case LIST_ITEM -> item.getListItem().getBase();
      case FORMULA -> item.getFormula().getBase();
      case TEXT -> item.getText().getBase();
      case FIELD_HEADING -> item.getFieldHeading().getBase();
      case FIELD_VALUE -> item.getFieldValue().getBase();
      // CodeItem inlines its base fields rather than wrapping TextItemBase
      // (see document.proto); this fold never emits one.
      case CODE, ITEM_NOT_SET -> null;
    };
  }
}
