# grpc-email design

## 1. Goals

- Feature parity with Docling `InputFormat.EMAIL` (`.eml`, `.msg`) and
  `EmailBackendOptions.list_attachments`.
- Project every message into the same `Document` data plane gRParse
  already emits, with a `CollectorSource` of `email`.
- Stream native events as they are known (headers first, then body
  parts, then attachments) so a large mailbox message does not wait for
  the last MIME part before the client sees the subject. This is a
  product difference from Docling, not a transport optimization: the
  stream is what UIs consume. A unary “whole Document” may exist as a
  convenience; it is not the live path.
- Diskless: uploaded bytes stay in memory; the container is read-only.

## 2. Non-goals (v1)

- Sending mail, IMAP/POP, or PST/OST stores (those are connectors).
- Rendering `.msg` through LibreOffice.
- Embedding attachment bytes in the `Document` by default.
- Decrypting S/MIME or verifying DKIM (headers are preserved; crypto is
  a later opt-in).
- Being a general OLE inspector. CFB that is not MAPI is `UNIMPLEMENTED`.

## 3. Wire API (sketch)

`ai.pipestream.email.v1.EmailParseService`

```text
rpc ParseEmail(stream ParseEmailRequest) returns (stream ParseEmailEvent);
rpc GetServiceInfo(GetServiceInfoRequest) returns (ServiceInfo);
```

First client message is options; subsequent messages are chunks with a
terminal `complete`.

Options:

- `list_attachments` (bool, default false) — Docling's knob. Names and
  content types only.
- `include_attachment_bytes` (bool, default false) — opt-in; still
  capped. The coordinator uses this when it will fan attachments out.
- `max_document_mib`
- `emit_document` (bool, default false) — opt into the Document projection
  of §4, emitted as one event just before the trailer.

Events, in order:

1. `EmailInfo` — detected format (`EML`/`MSG`), subject, addresses,
   dates, message-id, content-type of the root.
2. `BodyPart` — `text/plain` and/or `text/html`, charset already decoded
   to UTF-8. HTML is opaque payload for the HTML collector.
3. `Attachment` — filename, content type, size, content-id, optional
   bytes.
4. `Document` — only when `emit_document`; see §4.
5. `ParseStatus` — counts, warnings (e.g. unnamed parts).

Health + reflection, same as the other collectors.

## 4. Mapping to Document

**Implemented in this repo** — `ai.pipestream.email.document.EmailDocumentFold`,
behind the `emit_document` option, emitting `ParseEmailResponse.document`
once, immediately before the `ParseStatus` trailer. The fold consumes the
server's own outgoing events (the same `ParseEmailResponse` messages that go
to the wire) in one pass, so the projection cannot drift from the stream it
projects. The typed events remain the lossless wire; the Document is the
lossy structural view the collector fleet shares.

`document.proto` is vendored byte-identical from gRParse at
`proto/ai/pipestream/document/v1/document.proto`; it is never edited here,
and `buf.yaml` exempts it from the COMMENTS rule for that reason.

| Email | Document |
|---|---|
| subject | `Document.name` (falls back to `document_id`), **and** a `TitleItem` (`DOC_ITEM_LABEL_TITLE`) as the first `#/body` child |
| detected format | `origin.mimetype` — `message/rfc822` (EML) / `application/vnd.ms-outlook` (MSG); `CollectorSource.model` — `eml` / `msg` |
| `document_id` | `origin.filename` when non-empty |
| envelope facts | typed `email.*` key/values in the **body group's** `meta.custom_fields`: `email.from` / `to` / `cc` / `bcc` / `reply_to` / `sender` as `ListValue`s of `"Name <addr>"`, `email.date` / `received_date` as RFC 3339 strings, `email.message_id`, `email.in_reply_to`, `email.references` (list), `email.content_type`. An absent fact is an absent key, never an empty string |
| the raw header list | **not mapped.** Envelope facts are typed key/values, not a header dump; the lossless list stays on `EmailInfo` |
| `text/plain` body | one `TextItem` (`DOC_ITEM_LABEL_TEXT`, `CONTENT_LAYER_BODY`) per blank-line-separated paragraph, each with `meta.custom_fields["email.part_id"]`. Line endings are normalized and paragraphs stripped, so chunking or CRLF cannot change the item list |
| `text/html` body | **not mapped.** Emitted verbatim on the typed stream for the HTML collector, whose items merge into this fragment additively. Parsing HTML here would fork that job into two implementations |
| attachment listing | one `GROUP_LABEL_LIST` group named `attachments` under `#/body`, created only when an Attachment event was actually emitted, with a `ListItem` (`enumerated = false`) per attachment reading `filename (content_type, N bytes)` plus `email.part_id`. An unnamed part reads `(unnamed)`, an untyped one `unknown type` — no filename or MIME type is invented |
| inline image attachment | additionally a `PictureItem` under `#/body` when `inline` **and** `image/*` **and** a content id is set (the three conditions that make it referenceable from an HTML body), with `ImageRef{mimetype = content_type, uri = "part:" + part_id}` and `email.content_id` / `email.part_id` custom fields |
| attachment bytes | never in the Document. `ImageRef.uri` is a pointer into the typed stream (`part:1.3`, `part:attach:1`); the bytes ride `Attachment.data` under `include_attachment_bytes`, or a child parse the coordinator drives |
| counts and warnings | **not mapped.** `ParseStatus` closes the fold but contributes no items; a warning is stream metadata, not document structure |

Provenance for email has no page box: `prov` is left empty everywhere and
source locators (the MIME part id, the MAPI storage index) ride per-item
`custom_fields` instead. Items carry `CollectorSource{collector = "email",
model = eml|msg, version = the server build}` and no `confidence` — the
mapping is declarative and deterministic, so a confidence would be noise.
`field_regions` / `field_items` are never populated: the coordinator's
additive merge does not renumber them and would drop them silently.

Two merge-safety notes for whoever consumes this fragment. Root-level meta —
the body group's `custom_fields`, where the envelope facts live — is
**first-writer-wins** downstream: a merge will not overwrite keys another
collector already set, and this fold must not count on overwriting anyone
either. And the fragment is self-contained and densely numbered from zero,
referencing only `#/body`, `#/furniture`, and its own items, because the
merge renumbers refs as it splices; `EmailDocumentFold.integrityErrors`
checks exactly that and every fold test asserts it comes back empty.

## 5. `.msg` path

1. Magic `d0 cf 11 e0 a1 b1 1a e1` → CFB.
2. `MAPIMessage` reads named properties (subject, body, HTML body,
   recipients, attach rows).
3. Recipients use `PidTagRecipientType` (1 to / 2 cc / 3 bcc), matching
   Docling's oxmsg mapping.
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
