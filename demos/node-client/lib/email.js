// SPDX-License-Identifier: Apache-2.0
//
// Thin wrapper around the ai.pipestream.email.v1 gRPC contract.
//
// The protos are loaded dynamically from ../../../proto (the single source of
// truth in this repository) — no generated code is checked in. The well-known
// google/protobuf imports (timestamp, struct) resolve from protobufjs's
// bundled definitions, so no extra include dir is needed for them.

import { fileURLToPath } from "node:url";
import path from "node:path";
import grpc from "@grpc/grpc-js";
import protoLoader from "@grpc/proto-loader";

const PROTO_ROOT = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..", "..", "..", "proto",
);

const packageDefinition = protoLoader.loadSync(
  path.join(PROTO_ROOT, "ai", "pipestream", "email", "v1", "email_service.proto"),
  {
    includeDirs: [PROTO_ROOT],
    keepCase: false,
    longs: Number,
    enums: String,
    defaults: true,
    oneofs: true,
  },
);

const { ai } = grpc.loadPackageDefinition(packageDefinition);

/** Upload chunk size. Any value gives the same events; this one is quick. */
export const CHUNK_BYTES = 64 * 1024;

/** A connected grpc-email client. */
export class EmailClient {
  /** @param {string} address host:port of the grpc-email server. */
  constructor(address = process.env.GRPC_EMAIL_ADDR ?? "127.0.0.1:50054") {
    this.stub = new ai.pipestream.email.v1.EmailParseService(
      address,
      grpc.credentials.createInsecure(),
    );
  }

  /**
   * Open a ParseEmail call and send the options frame.
   *
   * The caller then writes `{ chunk: { data, complete } }` frames as the
   * message becomes available, marks the final chunk `complete: true`, and
   * calls `.end()`. This is the shape to use when the message is itself
   * arriving from somewhere, since it never holds the whole thing:
   * `server.js` pipes an HTTP upload straight through it.
   *
   * Note the ordering: options go out before anything reads the response,
   * matching the contract — the server reads exactly one options frame before
   * it accepts any chunk.
   *
   * @param {object} options a ParseEmailOptions message.
   * @returns {object} the duplex call.
   */
  openParse(options) {
    const call = this.stub.parseEmail();
    call.write({ options });
    return call;
  }

  /**
   * Stream a whole in-memory message and yield each event as it arrives.
   *
   * @param {Buffer} bytes the .eml or .msg file.
   * @param {object} options a ParseEmailOptions message.
   * @returns {AsyncGenerator<object>} ParseEmailResponse messages.
   */
  async *parse(bytes, options) {
    const call = this.openParse(options);

    if (bytes.length === 0) {
      call.end();
    } else {
      for (let at = 0; at < bytes.length; at += CHUNK_BYTES) {
        const data = bytes.subarray(at, at + CHUNK_BYTES);
        call.write({ chunk: { data, complete: at + CHUNK_BYTES >= bytes.length } });
      }
      call.end();
    }

    // grpc-js hands events to callbacks; this turns the callback stream into
    // an async iterator without buffering the whole message's worth.
    const queue = [];
    let waiting = null;
    let done = false;
    let failure = null;

    const wake = () => {
      if (waiting) {
        const resolve = waiting;
        waiting = null;
        resolve();
      }
    };
    call.on("data", (event) => { queue.push(event); wake(); });
    call.on("end", () => { done = true; wake(); });
    call.on("error", (err) => { failure = err; done = true; wake(); });

    for (;;) {
      while (queue.length > 0) yield queue.shift();
      if (done) break;
      await new Promise((resolve) => { waiting = resolve; });
    }
    if (failure) throw failure;
  }

  /** Server identity, capabilities, and limits (including the UiInfo tab ad). */
  getServiceInfo() {
    return new Promise((resolve, reject) => {
      this.stub.getServiceInfo({}, (err, response) => {
        if (err) reject(err); else resolve(response);
      });
    });
  }

  close() {
    grpc.closeClient(this.stub);
  }
}

/**
 * Render one event as a single stable line.
 *
 * @param {object} response a ParseEmailResponse.
 * @returns {string|null} the line, or null for an event with nothing to say.
 */
export function formatEvent(response) {
  const { emailInfo, bodyPart, attachment, status, document } = response;

  if (emailInfo) {
    const who = (role) =>
      emailInfo.addresses
        .filter((a) => a.role === `ADDRESS_ROLE_${role}`)
        .map((a) => (a.name ? `${a.name} <${a.address}>` : a.address))
        .join(", ");
    return [
      `info format=${emailInfo.format.replace("EMAIL_FORMAT_", "")}`,
      `subject=${quote(emailInfo.subject)}`,
      `from=${quote(who("FROM"))}`,
      `to=${quote(who("TO"))}`,
      `message_id=${quote(emailInfo.messageId)}`,
      `headers=${emailInfo.headers.length}`,
    ].filter((s) => !s.endsWith('=""') || s.startsWith("subject")).join(" ");
  }
  if (bodyPart) {
    return `body part=${bodyPart.partId} type=${bodyPart.contentTypeRaw}`
      + ` chars=${bodyPart.text.length}`
      + (bodyPart.charset ? ` charset=${bodyPart.charset}` : "");
  }
  if (attachment) {
    return `attachment index=${attachment.index} part=${attachment.partId}`
      + ` name=${quote(attachment.filename || "(unnamed)")}`
      + ` type=${attachment.contentType || "unknown"}`
      + ` size=${attachment.sizeBytes}`
      + (attachment.inline ? " inline" : "")
      + (attachment.contentId ? ` cid=${quote(attachment.contentId)}` : "");
  }
  if (document) {
    return `document name=${quote(document.name)}`
      + ` texts=${document.texts?.length ?? 0}`
      + ` groups=${document.groups?.length ?? 0}`
      + ` pictures=${document.pictures?.length ?? 0}`;
  }
  if (status) {
    return `status state=${status.state.replace("STATE_", "")}`
      + ` body_parts=${status.bodyParts} attachments=${status.attachments}`
      + ` bytes=${status.messageBytes}`
      + (status.warnings.length ? ` warnings=[${status.warnings.map(quote).join(",")}]` : "");
  }
  // An event this client has no name for. The contract says to ignore those
  // rather than fail: the oneof is the extension point.
  return null;
}

function quote(value) {
  let out = '"';
  for (const ch of value ?? "") {
    if (ch === "\\") out += "\\\\";
    else if (ch === '"') out += '\\"';
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else out += ch;
  }
  return out + '"';
}
