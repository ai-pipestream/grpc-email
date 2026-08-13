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

Events, in order:

1. `EmailInfo` — detected format (`EML`/`MSG`), subject, addresses,
   dates, message-id, content-type of the root.
2. `BodyPart` — `text/plain` and/or `text/html`, charset already decoded
   to UTF-8. HTML is opaque payload for the HTML collector.
3. `Attachment` — filename, content type, size, content-id, optional
   bytes.
4. `ParseStatus` — counts, warnings (e.g. unnamed parts).

Health + reflection, same as the other collectors.

## 4. Mapping to Document

gRParse (or this server, if it speaks Document directly) projects:

| Email | Document |
|---|---|
| subject | `Document.origin` filename / title metadata |
| headers | typed key/values on the origin, not a paragraph soup |
| `text/plain` body | `TextItem`s, `CONTENT_LAYER_BODY` |
| `text/html` body | handed to the HTML collector; its items merge additively |
| attachment listing | a `GroupItem` of filename/type lines when `list_attachments` |
| attachment bytes | child parse, `CollectorSource` still tagged |

Provenance for email has no page box. Items carry source collector
`email` and omit bbox rather than inventing one.

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
