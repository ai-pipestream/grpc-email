# Node demo client

Two programs against the same contract: a CLI streamer and a live web viewer.
Stubs are loaded dynamically from [`../../proto`](../../proto) at run time, so
nothing generated is checked in.

```bash
npm install

# CLI: one line per event
node cli.js --info                          # GetServiceInfo, including UiInfo
node cli.js ../sample-data/plain_text.eml
node cli.js ../sample-data/multipart_with_attachments.eml --document

# Web viewer, then open http://127.0.0.1:8090
npm start
```

Both honour `GRPC_EMAIL_ADDR` (default `127.0.0.1:50054`). The viewer also
takes `PORT` (default 8090).

### Serving under a base path

Set `UI_BASE` and the whole viewer moves under that prefix, for example behind
a reverse proxy that forwards `/ui/email/*` unchanged:

```bash
UI_BASE=/ui/email npm start   # page at http://127.0.0.1:8090/ui/email/
```

The bridge strips the prefix before routing, so every endpoint lives at
`$UI_BASE/api/*`, and it injects a `<meta name="ui-base">` tag into the served
page, which the page reads to prefix its own `fetch()` calls. Unset, nothing
changes: the bridge answers at the root exactly as before.

## The web viewer

The viewer exists to make one property visible: **the envelope arrives before
the upload finishes**.

It is a single HTTP request. The browser POSTs the message and reads
Server-Sent Events off the *same* response, which is deliberately the same
shape as the gRPC call underneath it: bytes going one way while events come
back the other. Nothing buffers the message. Each upload slice is written into
the gRPC call as it lands, and each event is flushed to the page as the Java
server emits it.

The page shows an upload bar with a green marker where the envelope landed,
and says so in words:

> Envelope after **256 B of 1.2 KiB** (21% uploaded). The rest of the message
> had not been sent yet.

That is the server keeping its own promise: for a chunked `.eml`, `EmailInfo`
is emitted as soon as the header block has arrived, not when the upload ends.

Two controls make that observable rather than theoretical:

- **Upload throttle** sleeps between upload slices. It slows the *upload*
  only. The parser is never waiting on anything but bytes.
- **Chunk size** is derived from the message, aiming for about 40 upload steps
  whatever the size, and shown next to the throttle.

Neither changes the events. Three checkboxes map straight onto
`ParseEmailOptions`: attachment descriptors (on by default here, off in the
contract), attachment payload bytes, and the Document fold.

The fixtures in [`../sample-data`](../sample-data) are hand-authored and tiny:

| Message | What you see |
|---|---|
| `plain_text.eml` | one envelope, one body part, done |
| `multipart_with_attachments.eml` | plain and HTML body parts, a regular attachment, and an inline image with a Content-ID |

### Why SSE is parsed by hand

`EventSource` only does `GET`, and the whole point is that the upload and the
event stream are one request. So the page reads `response.body` as a stream
and splits frames itself. It is about fifteen lines and it is in `stream()` in
`public/index.html`.

## Things that bite

**Mark the last chunk `complete`.** The contract treats a stream that
half-closes without a `complete` chunk as `INVALID_ARGUMENT`: a truncated
upload must not be mistaken for a short message. The bridge therefore holds
back exactly one slice until it knows whether another follows, and marks the
true final slice `complete: true`.

**Write the options frame before you read the response.** The server reads
exactly one options frame before accepting chunks. `lib/email.js` sends
options inside `openParse()` for exactly this reason, so the ordering cannot
be got wrong by a caller.

**Handle the oneof by name, not by guessing.** With `oneofs: true`,
proto-loader sets `message.event` to the name of the active arm. The bridge
forwards `message[message.event]` rather than sniffing which key is populated,
so an arm added to the contract later is passed through to the page instead of
being dropped silently.

**Backpressure is real and worth keeping.** `res.write()` returning false
means the browser is behind. The bridge pauses the gRPC call and resumes on
`drain`, which propagates through gRPC flow control back to the parser.
Without it, a large mailbox message queues the whole event stream in this
process.
