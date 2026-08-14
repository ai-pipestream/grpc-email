# grpc-email

gRPC collector for RFC 822 .eml and Outlook .msg, projecting into the gRParse Document data plane

A standalone gRPC server that turns email bytes into typed events: envelope,
body parts, attachments, status. It is not PipeStream core and not a Docling
Python wrapper — `oxmsg` / `mailparser` are the reference behaviour, not the
runtime. One JVM: Jakarta Mail for `.eml`, Apache POI HSMF for `.msg`.

Two things make it different from a Docling convert:

1. **The live stream is the product.** Docling hands you one document when
   everything is finished. This server emits the envelope the moment the
   header block has arrived — on a chunked `.eml` upload that is *before the
   client has finished sending* — then every body part and attachment as the
   MIME walk reaches it. `ParseStatus` is a trailer of counts and warnings,
   never the payload.
2. **Nothing touches disk.** Message bytes live in memory for the duration of
   the RPC. No temp files, no subprocesses; the container runs `--read-only`.

## Start here (humans and LLMs)

1. [`AGENTS.md`](AGENTS.md) — read order, definition of done, git
2. [`docs/architecture.md`](docs/architecture.md) — where this sits, language, live stream vs Docling
3. [`docs/design.md`](docs/design.md) — wire API, Document mapping, tests
4. [`docs/guidelines.md`](docs/guidelines.md) — fleet rules (streaming, proto, diskless, git)

## Wire API

`ai.pipestream.email.v1.EmailParseService` — [`proto/ai/pipestream/email/v1/email_service.proto`](proto/ai/pipestream/email/v1/email_service.proto)

```text
rpc ParseEmail(stream ParseEmailRequest) returns (stream ParseEmailResponse);
rpc GetServiceInfo(GetServiceInfoRequest) returns (GetServiceInfoResponse);
```

**Request.** Exactly one `ParseEmailOptions` first, then `EmailChunk`
messages in file order with `complete=true` on the last, then half-close.
Options: `document_id`, advisory `filename` / `content_type` (recorded, never
trusted), `list_attachments`, `include_attachment_bytes`, `max_document_mib`
(a client may lower the server's ceiling, never raise it), `emit_document`
(opt into the Document projection below).

**Response.** A `ParseEmailResponse` per event, `oneof event`:

| Event | When | Carries |
|---|---|---|
| `EmailInfo` | first, from headers alone | format, subject, role-tagged addresses, dates, message-id, in-reply-to, references, root content type, the full header list |
| `BodyPart` | per text part, in MIME order | `part_id`, `PLAIN`/`HTML` + `content_type_raw`, UTF-8 text, declared charset, MAPI source property for `.msg` |
| `Attachment` | per attachment, when `list_attachments` or `include_attachment_bytes` | index, filename, content type, size, content id, inline flag, optional bytes |
| `Document` | once, immediately before the trailer, only when `emit_document` | the whole message as one `ai.pipestream.document.v1.Document` |
| `ParseStatus` | last, exactly once | `STATE_OK` / `STATE_PARTIAL`, warnings, counts, message size |

Format is detected from the bytes (OLE2 signature, or an RFC 822 header block
that carries at least one real mail field). The advisory content type is never
consulted. HTML bodies are emitted verbatim for the HTML collector; they are
not parsed here. Attachment payloads stay off the wire unless asked for.

`GetServiceInfo` reports versions, supported formats, and the limits above,
for orchestrators and tool facades.

**Errors** are gRPC status codes, never a partial success dressed up as one:

| Code | Cause |
|---|---|
| `INVALID_ARGUMENT` | no bytes, no `complete` chunk, a chunk before options, repeated options, headers that never ended, a corrupt or truncated container |
| `UNIMPLEMENTED` | bytes are not email (including an OLE2 file that is not MAPI) |
| `RESOURCE_EXHAUSTED` | over the effective byte cap |
| `INTERNAL` | unexpected parser fault |

`grpc.health.v1.Health` and server reflection (v1 and v1alpha) are registered.

### Event ordering

Events follow MIME document order, which for every shape real mail uses
(`text/plain`, `multipart/alternative`, `multipart/mixed` with trailing
attachments) is envelope → plain → HTML → attachments → status. A message
that puts an attachment part *before* its text part gets those two events in
that order; the collector streams what it reads rather than holding parts
back to impose a grouping. `part_id` is the dotted MIME path, so clients that
care can re-sort.

## Document projection

`emit_document` turns on a second, lossy view of the same parse: the server
folds its own outgoing events into one `ai.pipestream.document.v1.Document`
and sends it as the `document` event, immediately before the trailer. The
typed events stay exactly as they were — a client can diff the two streams —
and with the option off the fold never runs.

The fold lives in [`EmailDocumentFold`](email-service/src/main/java/ai/pipestream/email/document/EmailDocumentFold.java),
is fed the very messages the server writes, and is single-pass. What it maps
is [`docs/design.md` §4](docs/design.md); the short version:

- subject → `Document.name` and a `TitleItem`, the first body child;
- envelope facts (addresses by role, dates, threading ids, root content type)
  → typed `email.*` key/values in the body group's `meta.custom_fields`, not
  a paragraph of prose and not the raw header list;
- each `text/plain` part → one `TextItem` per blank-line-separated paragraph,
  tagged with its `email.part_id`;
- listed attachments → a `GROUP_LABEL_LIST` group named `attachments`, one
  line per attachment;
- inline images (`inline`, `image/*`, with a content id) → a `PictureItem`
  whose `ImageRef.uri` is **`part:<part_id>`** — a pointer into the typed
  stream you are already reading (`part:1.3` for a MIME path, `part:attach:1`
  for a MAPI storage). Bytes are never embedded: a Document is one gRPC
  message, and attachment payloads belong on `Attachment.data`.

Not mapped, deliberately: HTML bodies (the HTML collector parses those, and
its items merge into this fragment downstream), the lossless header list, and
provenance — email has no pages, so `prov` stays empty rather than carrying
an invented page number.

## Formats

- **`.eml`** — Jakarta Mail over a `ByteArrayInputStream`, with the lenient
  MIME properties real mailboxes need. Encoded-word headers and filenames are
  decoded; a part whose declared charset does not exist is read as ISO-8859-1
  with a warning rather than failing.
- **`.msg`** — POI `MAPIMessage`. Recipients are role-tagged from
  `PidTagRecipientType` (1 to / 2 cc / 3 bcc), matching Docling's oxmsg
  mapping. Bodies come from `PidTagBody` and `PidTagHtml`; HTML wins over
  RTF, and an RTF-only message gets best-effort plain text with a
  `STATE_PARTIAL` warning — v1 ships no RTF layout engine. Transport headers
  are surfaced when the message kept them; MAPI properties are never dressed
  up as headers they are not.

Out of scope for v1: sending mail, IMAP/POP, PST/OST, S/MIME decryption, DKIM
verification, expanding embedded `message/rfc822` and Outlook attachments
(they are described so the coordinator can re-parse them).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `GRPC_EMAIL_PORT` | `50054` | Listen port |
| `GRPC_EMAIL_MAX_DOCUMENT_MIB` | `64` | Per-message byte cap (`RESOURCE_EXHAUSTED` above it) |
| `GRPC_EMAIL_MAX_ATTACHMENT_MIB` | `32` | Per-attachment payload cap for `include_attachment_bytes`; larger attachments are still described, just without their bytes |
| `GRPC_EMAIL_MAX_CONCURRENT_PARSES` | CPU cores | Parses in flight before queueing |
| `GRPC_EMAIL_METRICS_INTERVAL_SECONDS` | `60` | Metrics line interval, `0` disables |

Metrics are a stdout line on the interval:

```text
grpc-email metrics: messages{parsed=12,rejected=1,failed=0} content{body_parts=19,attachments=7,bytes=418233}
```

A client that sets `include_attachment_bytes` must raise its own
`maxInboundMessageSize`; gRPC's 4 MiB default will reject a large attachment
event.

## Build and run

```bash
./gradlew build                  # compiles and runs the full test suite
./gradlew :email-service:run     # listens on :50054

buf lint                         # proto: STANDARD + COMMENTS, no comment ignores
buf breaking --against '.git#branch=main'

docker build -t grpc-email .
docker run --rm --read-only -p 50054:50054 grpc-email
```

The image build runs the test suite, so an image never ships from a tree
whose tests did not pass. `--read-only` works because the server never
writes.

## Concurrency model

One parse per request on a virtual thread, with a semaphore bounding how many
run at once. The bound protects heap — MIME trees and MAPI property maps are
held whole in memory — not CPU. A poisoned message fails its own RPC and
nothing else.

## Tests

`./gradlew test` — 70 tests, no network, no committed binaries. Fixtures are
authored in memory: Jakarta Mail writes the `.eml`, and `MsgFixtures` builds
`.msg` bytes from the MS-OXMSG layout up (compound-file streams, property
chunks, recipient and attachment storages, an uncompressed
`PidTagRtfCompressed` stream), because POI can read `.msg` but not write one.

The liveness assertions are load-bearing: one test half-uploads a message and
requires the envelope to arrive before the rest is sent, another requires
every body part and attachment to be its own message ahead of the trailer.
Rework the server into a batch and they fail.

The Document fold is tested twice over: as a unit, on synthesized event
streams, with a structural integrity check (unique self refs, symmetric
parent/child links, no dangling refs) asserted on every document it builds;
and on the wire, where the document must arrive exactly once, immediately
before the trailer, saying the same things the typed events said.

## Remotes

- **Forgejo** (`git.rokkon.com/ai-pipestream/grpc-email`) is the source of truth. `main` lives here.
- **GitHub** is a public push-mirror of `main`. Do not merge to GitHub `main`.
- GitHub's default branch is `development` so LLM / `gh` work lands there instead of clobbering the mirror.

Push Forgejo first. GitHub `main` updates from the Forgejo push-mirror.
