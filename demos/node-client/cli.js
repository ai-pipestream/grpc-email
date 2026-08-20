#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// CLI streamer for the grpc-email server: one line per event.
//
//   node cli.js --info                       # GetServiceInfo, including UiInfo
//   node cli.js message.eml                  # parse, one line per event
//   node cli.js message.eml --document       # also request the Document fold
//   node cli.js message.eml --bytes          # attachment events carry data
//
// Environment: GRPC_EMAIL_ADDR (default 127.0.0.1:50054).

import { readFile } from "node:fs/promises";
import path from "node:path";
import { EmailClient, formatEvent } from "./lib/email.js";

const args = process.argv.slice(2);
const file = args.find((a) => !a.startsWith("--"));
const client = new EmailClient();

if (args.includes("--info")) {
  console.log(JSON.stringify(await client.getServiceInfo(), null, 2));
  client.close();
  process.exit(0);
}

if (!file) {
  console.error("usage: node cli.js [--info] <message.eml|message.msg> [--document] [--bytes]");
  process.exit(2);
}

const options = {
  documentId: "",
  filename: path.basename(file),
  contentType: "",
  listAttachments: true,
  includeAttachmentBytes: args.includes("--bytes"),
  emitDocument: args.includes("--document"),
};

try {
  for await (const event of client.parse(await readFile(file), options)) {
    const line = formatEvent(event);
    if (line) console.log(line);
  }
} catch (err) {
  console.error(`error code=${err.code} message=${JSON.stringify(err.details ?? err.message)}`);
  process.exitCode = 1;
} finally {
  client.close();
}
