# grpc-email design

## 1. Goals

- Parse `.eml` and `.msg`, with attachment listing.
- Project every message into the same `Document` data plane gRParse
  already emits, with a `CollectorSource` of `email`.
- Stream native events as they are known (headers first, then body
  parts, then attachments) so a large mailbox message does not wait for
  the last MIME part before the client sees the subject. This is a
  product decision, not a transport optimization: the stream is what
  UIs consume. A unary "whole Document" may exist as a convenience; it
  is not the live path.
- Diskless: uploaded bytes stay in memory; the container is read-only.

## 2. Non-goals (v1)

- Sending mail, IMAP/POP, or PST/OST stores (those are connectors).
- Expanding an embedded message in place. Its bytes are handed over
  whole, as a standalone `.msg`, for the coordinator to re-parse.
- Rendering `.msg` through LibreOffice.
- Embedding attachment bytes in the `Document` by default.
- Decrypting S/MIME or verifying DKIM (headers are preserved; crypto is
  a later opt-in).
- Being a general OLE inspector. CFB that is not MAPI is `UNIMPLEMENTED`.

## 3. Wire API

`ai.pipestream.email.v1.EmailParseService`, as declared in
[`proto/ai/pipestream/email/v1/email_service.proto`](../proto/ai/pipestream/email/v1/email_service.proto):

```text
rpc ParseEmail(stream ParseEmailRequest) returns (stream ParseEmailResponse);
rpc GetServiceInfo(GetServiceInfoRequest) returns (GetServiceInfoResponse);
```

First client message is options; subsequent messages are chunks with a
terminal `complete`.

Options:

- `omit_attachment_list` (bool, default false): suppress the attachment
  listing. Listing is the **default**, so a parse with no options at all
  still names every attachment; the polarity is inverted because proto3
  cannot express "unset means true" on a bool, and a Document that
  silently omits the PDFs a message carried is worse than one that names
  them. Attachments stay counted in `ParseStatus` either way.
- `list_attachments` (bool, default false): the historical opt-in, kept
  for older clients. Redundant now, but an explicit true still outranks
  `omit_attachment_list`.
- `include_attachment_bytes` (bool, default false): opt-in; still
  capped, and it overrides `omit_attachment_list` because a client that
  asked for payloads asked for the listing. The coordinator uses this
  when it will fan attachments out.
- `max_document_mib`: caller-requested byte cap; can lower the server's
  ceiling, never raise it.
- `emit_document` (bool, default false): opt into the Document projection
  of §4, emitted as one event just before the trailer.

Events, in order:

1. `EmailInfo`: detected format (`EML`/`MSG`), subject, addresses,
   dates, message-id, content-type of the root. An `Address` is a name and
   an addr-spec; a `.msg` party stored under an Exchange directory name
   also carries it in `native_address` (with `address_type`), and has an
   empty `address` plus a warning when the message holds no SMTP address
   for it. A header that is present and cannot be read (`Date`, the
   `Received` date clause, part of an address list) is named in
   `unparsed_headers` and warned about, so an unset `date` means "absent"
   only when the header is not listed there.
2. `BodyPart`: `text/plain` and/or `text/html`, charset already decoded
   to UTF-8 (`charset_detected`, `charset_fallback` and
   `undecodable_sequences` say when the declared charset did not fit the
   bytes). HTML is opaque payload for the HTML collector.
3. `Attachment`: filename, content type, size, content-id, optional
   bytes. A `.msg` attachment with no stored content (attached by
   reference, or its data stream lost) is listed with size 0 and a
   warning.
4. `Document`: only when `emit_document`; see §4.
5. `ParseStatus`: counts, warnings (e.g. unnamed parts).

Health + reflection, same as the other collectors.

### TNEF containers

A `winmail.dat` (`application/ms-tnef`) is a container, not an attachment
anyone can open, so an attachment whose bytes carry the TNEF signature is
unpacked in place, in `.eml` and `.msg` alike. The attachments it carries are
emitted as ordinary `Attachment` events whose `part_id` is the container's own
part id plus `/attach:<n>`; the container itself is not listed. Its body
(`PidTagBody` or `attBody`, `PidTagHtml`, or `PidTagRtfCompressed` reduced to
plain text with a warning, exactly as a `.msg` body is chosen) is emitted only
when the message has emitted no body of its own, because a rich-text message
normally carries a plain rendering of the same body beside its container;
those parts are `<part_id>/body:plain|html|rtf` and set `source_property`.
Records are framed here and only MAPI property lists are decoded by POI's
HMEF, whose own reader drops every attachment when one record is damaged;
here a damaged record is skipped, one warning per container counting them
all, and a stream cut short keeps what came before the cut. A container
with no readable record is described as the opaque attachment it then is.
Nothing found inside a container is unpacked again.

POI refuses a whole MAPI property list when one property in it is over its
per-property ceiling, which defaults to 1 MB, so an HTML or RTF body over
1 MB would cost every body in the list. The service raises POI's
per-property and per-record ceilings to its own upload cap
(`GRPC_EMAIL_MAX_DOCUMENT_MIB`) when it starts: a body as large as the
message that carries it is read, and a property claiming more than that is
refused before anything is allocated for it.

## 4. Mapping to Document

**Implemented in this repo** as `ai.pipestream.email.document.EmailDocumentFold`,
behind the `emit_document` option, emitting `ParseEmailResponse.document`
once, immediately before the `ParseStatus` trailer. The fold consumes the
server's own outgoing events (the same `ParseEmailResponse` messages that go
to the wire) in one pass, so the projection cannot drift from the stream it
projects. The typed events remain the lossless wire; the Document is the
lossy structural view the collector fleet shares.

`document.proto` is vendored byte-identical from gRParse at
`proto/ai/pipestream/document/v1/document.proto`; it is never edited here, and
`buf.yaml` exempts it from the COMMENTS rule for that reason and from the
breaking check for the same one. Resyncing it is a copy, never a merge: when
the canonical schema grows a typed home for something this collector was
projecting as text, the fold moves onto the typed field and stops writing the
string, in the same commit as the resync.

| Email | Document |
|---|---|
| subject | `Document.name` (falls back to `document_id`), **and** a `TitleItem` (`DOC_ITEM_LABEL_TITLE`) as the first `#/body` child |
| detected format | `origin.mimetype`: `message/rfc822` (EML) / `application/vnd.ms-outlook` (MSG); `CollectorSource.model`: `eml` / `msg` |
| `filename` option | `origin.filename`. `document_id` is **not** used: it is a caller-chosen correlation id, and writing it into the field that means "the name of the source file" made every Document claim a filename the message never had. A caller who declared nothing leaves the field empty |
| `content_type` option | `origin.declared_mimetype`. Advisory only, and recorded where it cannot be mistaken for `origin.mimetype`, which stays the type detected from the bytes. The two disagreeing is a fact worth keeping, not one to resolve here. A caller who declared nothing leaves the field unset |
| the uploaded bytes | `origin.binary_hash`: the first eight bytes of their SHA-256, big-endian, as an unsigned 64-bit value. The message is buffered for the parse anyway, so the dedup and integrity key costs one digest. A fold never handed the bytes leaves it zero, which still means unknown |
| envelope | `Document.email` (`EmailMeta`), the schema's own typed slot: `from` / `to` / `cc` / `bcc` as repeated `EmailParty{name, address}`, one party per mailbox with the display name and the addr-spec kept apart, and `native_address` / `address_type` beside them when the mailbox was stored as an Exchange or X.400 address (copied from `Address.native_address` / `address_type`; `address` stays the SMTP address or empty and never holds a directory name); `message_id`; `in_reply_to` and `references` as repeated ids, one per element and never a joined string; `conversation_topic`; `conversation_index` as the **bytes** the packed structure is, copied from `EmailInfo.conversation_index_raw` (bytes on the typed stream too; the old hex string field 14 is retired and never set); `sent` as a `Timestamp` with `sent_raw` keeping the Date header's own spelling. An absent fact is an unset field, never an empty one |
| unparseable dates | the twin rule the schema states, applied everywhere it has twins: the typed field carries the parsed instant, the `_raw` twin carries the source's spelling, and a header that does not parse sets **only** the raw twin rather than inventing an instant. A `.msg` whose date came from `PidTagClientSubmitTime` has the instant and no spelling, which is equally honest |
| envelope, the rest | also on `EmailMeta`, typed: `reply_to` and `sender` as repeated `EmailParty`; `received` as a `Timestamp` (delivery time, which is not a modification time and does not belong in `modified`) with `received_raw` keeping the date clause of the topmost `Received` header as the hop wrote it, under the same twin rule as `sent`; `content_type`, the root part's declared type with its parameters, as against `origin.mimetype`, the detected container type. These used to be `Value` / `Struct` entries in the body group's `meta.custom_fields` (`email.reply_to`, `email.sender`, `email.received_date`, `email.content_type`); those keys are gone and nothing replaces them in a map |
| envelope, again | `Document.source_meta` (`DocumentMeta`): subject as `title`, origination date as the typed `created` `Timestamp` plus `created_raw`. This is the cross-collector slot: a consumer asking "what is this called and when was it written" reads the same fields whatever the source was. `extra` is left empty: every envelope fact has a typed home and is written there and only there. An envelope with nothing in it produces no `source_meta` at all |
| the raw header list | **not mapped.** Envelope facts are typed key/values, not a header dump; the lossless list stays on `EmailInfo` |
| `text/plain` body | one `TextItem` (`DOC_ITEM_LABEL_TEXT`, `CONTENT_LAYER_BODY`) per blank-line-separated paragraph, each naming its part in a second `source` entry, `SourceType.mime_part` (`MimePartSource{part_id, content_type}`). Line endings are normalized and paragraphs stripped, so chunking or CRLF cannot change the item list |
| `text/html` body | **not parsed, but named.** Emitted verbatim on the typed stream for the HTML collector, whose items merge into this fragment additively; parsing HTML here would fork that job into two implementations. The fold adds no items for it and registers it in `Document.attachments` as `SubDocumentRef{id = "part:" + part_id, media_type = "text/html", size_bytes = UTF-8 length of the text, kind = "body"}`, with no name and no `item_ref`, so an HTML-only message does not fold into a Document that looks body-less |
| attachment listing | one `GROUP_LABEL_LIST` group named `attachments` under `#/body`, created only when an Attachment event was actually emitted, with a `ListItem` (`enumerated = false`) per attachment reading `filename (content_type, N bytes)`, with the attachment's `MimePartSource` (`part_id`, `content_id` when it has one, `content_type`) as its second `source` entry. An unnamed part reads `(unnamed)`, an untyped one `unknown type`; no filename or MIME type is invented |
| attachment registry | additionally one `SubDocumentRef` in `Document.attachments` per attachment: `id = "part:" + part_id` (the same pointer the inline-image `ImageRef` uses), `name`, `media_type`, `size_bytes`, and `item_ref` naming the list item that mentions it. This is the fan-out surface: every field is typed rather than fused into the list item's display string, and the `(unnamed)` / `unknown type` substitutions are display-only and never leak into it. Keyed on the part id rather than the filename because a filename is neither required nor unique in a MIME message |
| inline image attachment | additionally a `PictureItem` under `#/body` when `inline` **and** `image/*` **and** a content id is set (the three conditions that make it referenceable from an HTML body), with `ImageRef{mimetype = content_type, uri = "part:" + part_id}` and a second `source` entry, `MimePartSource{part_id, content_id, content_type}`, so the `cid:` an HTML body uses can be matched without reading a map |
| attachment bytes | never in the Document. `ImageRef.uri` is a pointer into the typed stream (`part:1.3`, `part:attach:1`); the bytes ride `Attachment.data` under `include_attachment_bytes`, or a child parse the coordinator drives |
| counts and warnings | **not mapped.** `ParseStatus` closes the fold but contributes no items; a warning is stream metadata, not document structure |

Provenance for email has no page box: `prov` is left empty everywhere and
source locators (the MIME part id, the MAPI storage index) ride per-item as a
typed `SourceType.mime_part` instead. Items carry `CollectorSource{collector =
"email", model = eml|msg, version = the server build}` as their first `source`
entry and no `confidence`, because the mapping is declarative and
deterministic and a confidence would be noise; an item folded from a part
carries the `MimePartSource` second, and the title, which comes from a header,
carries the collector alone. The fold writes no `custom_fields` and no `extra`
anywhere in the Document.
`field_regions` / `field_items` are never populated: the coordinator's
additive merge does not renumber them and would drop them silently.

Two merge-safety notes for whoever consumes this fragment. Root-level facts
(`Document.email`, `source_meta`, `origin`) are written once, by this fold,
for the message it parsed: it must not count on overwriting what another
collector already set at the root of a merged Document. And the fragment is self-contained and densely numbered from zero,
referencing only `#/body`, `#/furniture`, and its own items, because the
merge renumbers refs as it splices; `EmailDocumentFold.integrityErrors`
checks exactly that and every fold test asserts it comes back empty.

## 5. `.msg` path

1. Magic `d0 cf 11 e0 a1 b1 1a e1` means CFB.
2. `MAPIMessage` reads named properties (subject, body, HTML body,
   recipients, attach rows).
3. Recipients use `PidTagRecipientType` (1 to / 2 cc / 3 bcc).
4. If an HTML body exists it wins over RTF; RTF-only messages emit
   plain text extracted from the RTF, with a warning. We do not ship a
   full RTF layout engine in v1.
5. Attachments become the same `Attachment` events as `.eml`.

The RFC 822 projection is an internal convenience so both formats share
one MIME walk. It is not the wire format.

## 6. Tests

Fixtures authored in-memory: Jakarta Mail builds `.eml`; POI HSMF (or a
checked-in tiny `.msg` generated in the test) builds Outlook. Assert
headers, body, and attachment names against what the test placed. No
production mail in the tree.

The Document fold is tested on both sides of the wire: `EmailDocumentFold`
against synthesized event streams (structure, integrity, source stamps,
custom fields, what is deliberately absent), and `EmailDocumentWireTest`
through the real service, where the document must arrive exactly once
immediately before the trailer, agree with the typed events of the same
stream, and vanish entirely when `emit_document` is not set.
