#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Web demo: a dependency-light HTTP bridge in front of grpc-email.
//
// The interesting part is that there is only one request. The browser POSTs an
// email file and reads Server-Sent Events off the *same* response, so the HTTP
// call has the same shape as the gRPC call underneath it: bytes flowing one
// way while events flow the other. Nothing here buffers the message; each
// upload chunk is written into the gRPC call as it lands, and each event is
// flushed to the browser as the Java server emits it.
//
// That is what the page is for. The envelope (subject, from, to, date) appears
// while the upload bar is still filling, because the server emits EmailInfo as
// soon as the header block has arrived — before the client has finished
// sending.
//
//   node server.js            # http://127.0.0.1:8090
//
// Environment: GRPC_EMAIL_ADDR (default 127.0.0.1:50054), PORT (default 8090),
// UI_BASE (default empty; serve everything under this path prefix instead).

import { createServer } from "node:http";
import { readFile, readdir, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { EmailClient } from "./lib/email.js";

const PORT = Number(process.env.PORT ?? 8090);
const ADDR = process.env.GRPC_EMAIL_ADDR ?? "127.0.0.1:50054";
const client = new EmailClient(ADDR);
const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), "public");

/**
 * Path prefix the whole bridge is served under, e.g. `/ui/email` behind a
 * reverse proxy that forwards without stripping. Empty means the bridge
 * answers at the root, byte-for-byte as it always has.
 */
const UI_BASE = normalizeBase(process.env.UI_BASE ?? "");

function normalizeBase(base) {
  if (!base) return "";
  const withSlash = base.startsWith("/") ? base : `/${base}`;
  return withSlash.replace(/\/+$/, "");
}

/** Largest artificial upload delay accepted, in ms per chunk. */
const MAX_DELAY_MS = 2000;

/**
 * Bytes per chunk fed into the gRPC call.
 *
 * The bridge re-slices the incoming body rather than forwarding whatever
 * Node's HTTP layer happened to hand it, because for a small file that is one
 * buffer, and a single chunk makes the whole demo invisible: the upload bar
 * would jump to full before the envelope arrived. Chunk size does not change
 * the events. It only changes how much of this you get to watch.
 */
const DEFAULT_CHUNK_BYTES = 256;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Where the fixtures live. Resolved once, and used to bound path lookups. */
const sampleDir = path.resolve(publicDir, "..", "..", "sample-data");

/** The fixtures, with their sizes. */
async function listSamples() {
  const found = [];
  let entries;
  try {
    entries = await readdir(sampleDir);
  } catch {
    return found; // no fixtures shipped
  }
  for (const entry of entries.filter((e) => e.endsWith(".eml") || e.endsWith(".msg")).sort()) {
    const { size } = await stat(path.join(sampleDir, entry));
    found.push({ name: entry, size });
  }
  return found;
}

function sendJson(res, status, body) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

/** Format one SSE event frame. */
function frame(event, data) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/** Build ParseEmailOptions from the query string. */
function optionsFrom(url) {
  return {
    documentId: url.searchParams.get("documentId") ?? "",
    filename: url.searchParams.get("filename") ?? "",
    // Attachment descriptors are the interesting half of the demo, so the
    // default here differs from the contract default: on, unless told no.
    listAttachments: url.searchParams.get("listAttachments") !== "0",
    includeAttachmentBytes: url.searchParams.has("includeBytes"),
    emitDocument: url.searchParams.has("emitDocument"),
  };
}

/**
 * The Document projection can be large next to a one-line event, and the page
 * only announces it. Summarize it in the bridge.
 */
function summarizeDocument(doc) {
  return {
    name: doc.name ?? "",
    texts: doc.texts?.length ?? 0,
    groups: doc.groups?.length ?? 0,
    pictures: doc.pictures?.length ?? 0,
    tables: doc.tables?.length ?? 0,
  };
}

/**
 * Pipe one upload through ParseEmail, streaming events back on the same
 * response.
 *
 * `delayMs` sleeps between upload chunks. It is there to make the streaming
 * visible on a small local file, where the whole thing would otherwise be
 * uploaded and parsed inside a single frame of animation. It slows the upload
 * only; the parser is never waiting on anything but bytes.
 */
async function bridge(req, res, url) {
  const options = optionsFrom(url);
  const delayMs = Math.min(Number(url.searchParams.get("delayMs") ?? 0) || 0, MAX_DELAY_MS);

  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
    // The page is watching for the first event; a proxy that buffers would
    // hide the only thing this demo exists to show.
    "x-accel-buffering": "no",
  });

  const call = client.openParse(options);
  let closed = false;

  // proto-loader's `oneofs: true` sets `message.event` to the name of the
  // active arm, so an arm this bridge has never heard of still forwards
  // rather than being dropped on the floor.
  call.on("data", (message) => {
    const kind = message.event;
    if (!kind) return;
    let payload = message[kind] ?? {};
    if (kind === "attachment") {
      // Payload bytes do not belong in an SSE feed; their size does.
      const { data, ...descriptor } = payload;
      payload = { ...descriptor, dataBytes: data?.length ?? 0 };
    } else if (kind === "document") {
      payload = summarizeDocument(payload);
    }
    if (!res.write(frame(kind, payload))) {
      // The browser is behind. Stop pulling events until it drains, rather
      // than queueing the whole message's worth in this process. The pause
      // propagates back through gRPC flow control to the parser itself.
      call.pause();
      res.once("drain", () => call.resume());
    }
  });
  call.on("error", (err) => {
    if (!closed) {
      closed = true;
      res.write(frame("grpc-error", { message: err.details ?? err.message }));
      res.end();
    }
  });
  call.on("end", () => {
    if (!closed) {
      closed = true;
      res.write(frame("done", {}));
      res.end();
    }
  });

  // If the browser goes away, stop parsing rather than finish into a void.
  res.on("close", () => {
    if (!closed) {
      closed = true;
      call.cancel();
    }
  });

  const chunkBytes = Math.max(
    1,
    Number(url.searchParams.get("chunkBytes") ?? 0) || DEFAULT_CHUNK_BYTES,
  );

  // The contract wants `complete: true` on the final chunk, which the bridge
  // only learns when the upload ends. So one slice is always held back: it is
  // written when the next slice arrives, and the last one goes out marked
  // complete. Nothing is ever held beyond that one slice.
  let fed = 0;
  let held = null;
  try {
    for await (const buffer of req) {
      for (let at = 0; at < buffer.length; at += chunkBytes) {
        if (closed) break;
        const chunk = buffer.subarray(at, at + chunkBytes);
        if (held) call.write({ chunk: { data: held, complete: false } });
        held = chunk;
        fed += chunk.length;
        // The page draws its upload bar from this, so it measures what the
        // parser has actually been handed, not what the browser has queued.
        res.write(frame("fed", { bytes: fed }));
        if (delayMs > 0) await sleep(delayMs);
      }
      if (closed) break;
    }
    if (!closed) {
      // An empty upload sends no complete chunk at all, and the server answers
      // INVALID_ARGUMENT, which arrives as a grpc-error frame like any other.
      if (held) call.write({ chunk: { data: held, complete: true } });
      call.end();
    }
  } catch {
    if (!closed) {
      closed = true;
      call.cancel();
      res.end();
    }
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  // The router sees the path with the base stripped, so every route below is
  // written against the root and works identically with or without UI_BASE.
  let pathname = url.pathname;
  if (UI_BASE && (pathname === UI_BASE || pathname.startsWith(`${UI_BASE}/`))) {
    pathname = pathname.slice(UI_BASE.length) || "/";
  }
  try {
    if (req.method === "POST" && pathname === "/api/parse") {
      return await bridge(req, res, url);
    }

    if (req.method === "GET" && pathname === "/api/samples") {
      return sendJson(res, 200, { files: await listSamples() });
    }

    // The path is resolved and then checked to be inside the sample directory
    // rather than pattern matched. Prefix checks on unresolved strings are how
    // directory traversal gets through.
    const sample = pathname.match(/^\/api\/samples\/(.+)$/);
    if (req.method === "GET" && sample) {
      const file = path.resolve(sampleDir, decodeURIComponent(sample[1]));
      if (!file.startsWith(sampleDir + path.sep)) {
        return sendJson(res, 403, { error: "outside the sample directory" });
      }
      const { size } = await stat(file);
      res.writeHead(200, {
        "content-type": "message/rfc822",
        "content-length": size,
      });
      return res.end(await readFile(file));
    }

    // Static front end. no-store: this is a live demo page, never let the
    // browser run a stale copy of it. When a base is configured the page is
    // told about it through a meta tag, and prefixes its own calls with it.
    if (req.method === "GET" && (pathname === "/" || pathname === "/index.html")) {
      let html = await readFile(path.join(publicDir, "index.html"), "utf8");
      if (UI_BASE) {
        html = html.replace("</head>", `<meta name="ui-base" content="${UI_BASE}">\n</head>`);
      }
      res.writeHead(200, {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "no-store",
      });
      return res.end(html);
    }
    if (req.method === "GET" && pathname === "/favicon.ico") {
      res.writeHead(204);
      return res.end();
    }

    sendJson(res, 404, { error: "not found" });
  } catch (err) {
    sendJson(res, 502, { error: err.details ?? err.message });
  }
});

server.on("error", (err) => {
  if (err.code === "EADDRINUSE") {
    console.error(`port ${PORT} is already in use, another bridge instance?`);
    console.error(`stop it, or run with a different port: PORT=8091 npm start`);
    process.exit(1);
  }
  throw err;
});

server.listen(PORT, () => {
  console.log(`email web demo on http://127.0.0.1:${PORT}${UI_BASE || "/"}`);
  console.log(`forwarding to grpc-email at ${ADDR}`);
});
