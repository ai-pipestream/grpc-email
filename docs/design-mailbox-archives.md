# grpc-email design: mailbox archives (mbox, PST/OST)

**Status:** design, no implementation yet. Single-message `.eml` and `.msg`
parsing exists and is unchanged by this document.
**Updated:** 2026-09-03

## 1. Motivation

The service today parses one message per request. Real corpora are not one
message: they are mailboxes. Mail arrives from mailers and archivers as
mbox; legal and compliance hauls arrive as PST (and occasionally its
offline sibling OST). A caller holding a 4 GB PST of ten thousand messages
should not have to split the container client-side, invent a folder
mapping, and lose the byte offsets that make the container itself
citable. The container is the document the caller has; the service should
accept it.

What changes is the envelope, not the message: every message inside a
container still flows through the existing `.eml` / `.msg` parse path and
produces the same typed events, plus container provenance (where in the
file this message lives, which folder held it).

## 2. Non-goals

- Editing or writing mailboxes. Read-only, like the rest of the service.
- IMAP/POP/JMAP or any live mail protocol. Still connectors, still not
  this service.
- Recovering deleted items from PST unallocated space, or repairing
  corrupt indexes beyond what the underlying library does on its own.
- Fanning every message into a full child parse inside this service. The
  container walk emits messages; the coordinator decides which ones
  deserve attachment fan-out.
- OST-specific sync state. OST is accepted as a container format only;
  its offline-sync metadata is ignored.

## 3. The two containers are different problems

**mbox is easy.** It is a flat file of RFC 822 messages separated by
`From ` lines. Splitting is a streaming scan: read bytes, find the
separator at a line start (with `>From` quoting honored), hand each slice
to the existing parser. No new dependency, no native code, constant
memory per message, and byte offsets fall out of the scan for free.
Quirk handling is bounded and well documented (`Content-Length`
disagreements, escaped separators), and a split that cannot trust its
boundaries fails the container rather than guessing.

**PST/OST is not.** The format is a proprietary B-tree database; the only
serious open reader is libpff, and libpff is C. This service is Java on a
read-only, diskless container, so every option is wrong somewhere:

1. **JNI/JNA binding to libpff.** No new process, and libpff's file API
   can read a sealed memfd through `/dev/fd`, keeping the diskless
   doctrine. But a native library in-process shares the JVM's fate:
   libpff parsing a hostile or merely corrupt multi-GB archive is exactly
   the workload that segfaults, and a segfault kills the service, not the
   RPC. That trades the fleet's isolation rule for deployment
   convenience. It also puts native library packaging inside the hardened
   Java runtime image.
2. **Shell out to an export tool** (libpff's `pffexport`, or libpst's
   `readpst`). Real process isolation, but the tools are batch exporters:
   they walk the store and write a directory tree of files. That means a
   writable filesystem (a RAM tmpfs satisfies the doctrine the way
   LibreOfficeKit uses one), parsing exported filenames back into folder
   structure, and no byte-range provenance into the container at all.
   Provenance is a stated goal, so this option fails on its own terms.
3. **A small native companion binary** that links libpff, reads the
   container from a sealed memfd via `/dev/fd`, and streams framed output
   on stdout: per message, its folder path, its container offset and
   length where the library reports them, and the message bytes (PST
   stores MAPI bodies; the companion emits what libpff yields per item
   and the Java side treats each item as a `.msg`-class parse). The JVM
   spawns one companion per container parse, reads its stream with
   backpressure, and kills it on timeout, exactly the shape grpc-asr
   already uses for ffmpeg children over memfd.

**Decision: option 3, the companion process.** Crash isolation is the
deciding factor: "one bad message must not kill the mailbox" extends to
"one corrupt container must not kill the service", and only a process
boundary delivers that against a C parser. The memfd input keeps the
service diskless; the framed stream keeps memory bounded; the companion
is a build artifact in the image, not a service. JNI remains the fallback
if the companion proves undeployable, and the tool-export path is
rejected outright for losing provenance.

## 4. Wire contract extension

Same service, same port (50054), new RPC beside the existing one:

```text
rpc ParseMailbox(stream ParseMailboxRequest) returns (stream ParseMailboxResponse);
```

First client message is options (a `ParseMailboxOptions` carrying the
`max_document_mib`-style caps, `emit_document`, and a
`max_messages` circuit breaker); subsequent messages are container bytes
with a terminal `complete`, the same upload shape as `ParseEmail`.

Events, in order:

1. `MailboxInfo`: detected format (`MBOX` / `PST` / `OST`), and for PST
   the folder tree (paths only) as soon as the library has walked it.
   Message count when the container declares one; zero when it can only
   be known by finishing.
2. Per message, in container order:
   - `MailboxItem`: provenance first. `offset` and `length` (byte range
     into the container; mbox offsets are exact, PST offsets are what
     libpff reports and may be absent, in which case the fields stay
     unset rather than invented), `folder_path` (empty for mbox), and a
     sequence number.
   - The message's own events, reused verbatim from the existing
     contract: `EmailInfo`, `BodyPart`, `Attachment`, optional
     `Document` when `emit_document` is set (one Document per message,
     emitted immediately before that message's terminal event, matching
     the single-message fold).
   - `MessageStatus`: per-message counts and warnings, closing that
     message.
   - or, instead of the message's events, `MessageError`: see the error
     taxonomy below.
3. `ParseStatus`: mailbox-level counts (messages emitted, messages
   failed, bytes consumed), warnings, the trailer.

`ParseEmail` is untouched; `buf breaking` against the previous contract
must stay clean, and the new messages live in the same proto file family
with new field numbers and new message types only.

## 5. Backpressure and size limits

A multi-GB PST cannot be buffered whole. The upload stream is chunked as
today, and the container is spooled into a sealed memfd as it arrives, so
resident cost is the container itself in RAM-backed pages, never the
container plus a copy. For mbox there is no spool: the splitter consumes
the growing upload buffer directly and emits as separators land, so the
first message's `EmailInfo` can precede the client's `complete`.

Caps, following the fleet doctrine that the caller may lower but never
raise:

- `GRPC_EMAIL_MAX_MAILBOX_MIB`: container byte ceiling, default
  generous but finite; exceeded is `RESOURCE_EXHAUSTED`.
- `max_document_mib` per message, as today: an oversized message is a
  `MessageError`, not a mailbox failure.
- `max_messages`: a circuit breaker against decompression-bomb-shaped
  containers claiming millions of items.
- A wall-clock budget per message and per container; an ffmpeg-style
  inactivity timeout kills a wedged companion child.

## 6. Error taxonomy

One bad message must not kill the mailbox.

- **Message-level failure** (a message slice that does not parse, an
  oversized message, a MAPI item whose body is unreadable): emitted as
  `MessageError` carrying the `MailboxItem` provenance, the gRPC status
  code the single-message parse would have returned, and a message. The
  walk continues with the next item. `ParseStatus` counts it.
- **Container-level failure** (bad magic, a PST the library cannot open,
  a truncated upload, a companion crash): the RPC fails with
  `INVALID_ARGUMENT` for the bytes' fault and `INTERNAL` for the tools'
  fault, after emitting whatever complete messages were already walked.
  Events already on the wire stay valid; the stream never retracts.
- **Ambiguous boundaries** (mbox separator disagreement): the splitter
  refuses to guess and the container fails `INVALID_ARGUMENT` naming the
  offset where certainty was lost.

## 7. Tests

- mbox fixtures authored in-memory: a three-message mbox with `>From`
  quoting asserts three messages, each parseable, each carrying the exact
  byte range the test computed by hand.
- A message deliberately corrupted mid-mbox produces one `MessageError`
  and two good messages; counts in `ParseStatus` agree.
- PST fixtures: a minimal PST generated once and checked in small (the
  same fixture discipline as the `.msg` tests; no production mail in the
  tree). Folder paths are asserted against the tree the generator wrote.
- Stream-liveness in the family shape: for mbox, a message event arrives
  before the input is fully consumed; `ParseStatus` is a trailer.
- The kill test: a companion that exits non-zero mid-walk fails the RPC
  `INTERNAL`, and the next `ParseMailbox` on the same server succeeds.
- `buf lint` clean; `buf breaking` against `development` clean.

## 8. Milestones

- **M1: mbox.** Splitter, `ParseMailbox` with the event contract above,
  offset provenance, error taxonomy, tests. No native code ships yet.
- **M2: PST via companion.** The companion binary (Rust or thin C over
  libpff, decided at build time by which links cleanest in the image),
  memfd input, framed stdout protocol, folder paths, kill-test isolation.
- **M3: OST and hardening.** OST accepted through the same path, size and
  circuit-breaker defaults tuned against real corpus sizes, coordinator
  wiring (how gRParse routes a mailbox and fans messages out) settled in
  the gRParse repo.
