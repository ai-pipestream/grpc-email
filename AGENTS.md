# AGENTS.md — grpc-email

You are implementing **grpc-email** from scratch in this repo. There is no
application code yet. Specs are the source of truth.

## Read this first, in order

1. This file
2. `docs/architecture.md` — fleet boundary, language, what we refuse to own
3. `docs/design.md` — wire API sketch, Document mapping, tests
4. `docs/guidelines.md` — fleet rules (streaming, proto, git, tests)

Do not start coding until those four are in your context. If architecture
and an existing sibling disagree on *process* (diskless, health, buf),
follow the sibling. If they disagree on *product* (live stream, Document
plane), follow architecture.md.

## This service

gRPC collector for RFC 822 .eml and Outlook .msg, projecting into the gRParse Document data plane

- **Language:** Java (Quarkus or plain grpc-java + virtual threads, matching grPOIc)
- **Copy from:** /work/main/grpc-services/grPOIc
- **Stack:** Jakarta Mail for `.eml`; Apache POI HSMF (`MAPIMessage`) for `.msg`. One JVM, no Python oxmsg in the serving path.
- **Live stream:** EmailInfo (headers) first, then BodyPart, then Attachment, then ParseStatus.

## Definition of done (v1)

ParseEmail bidi stream, GetServiceInfo, health, reflection, in-memory .eml/.msg fixtures, read-only Docker image, byte cap.

Also: README with build/run; proto lint clean; tests that fail if someone
turns the stream back into a batch (assert an event before the input is
fully consumed, or per-item events before Complete).

## Workspace

Checkout path: `/work/main/grpc-services/grpc-email`.
Git: `origin` = Forgejo (push `main` here). `github` = GitHub mirror.
Never merge GitHub `main`. See `docs/guidelines.md`.

gRParse wiring (`COLLECTOR_*` enum, endpoint env) is a **follow-up**.
Ship a working server in this repo first.
