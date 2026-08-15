# AGENTS.md: grpc-email

grpc-email is implemented in this repo: server, `.eml` and `.msg` parsers,
Document fold, and tests all exist. The specs under `docs/` remain the source
of truth for intent. Where a spec and the code disagree, treat the code as
right and fix the spec.

## Read this first, in order

1. This file
2. `docs/architecture.md`: fleet boundary, language, what we refuse to own
3. `docs/design.md`: wire API, Document mapping, tests
4. `docs/guidelines.md`: fleet rules (streaming, proto, git, tests)

Do not change behavior until those four are in your context. If architecture
and an existing sibling disagree on *process* (diskless, health, buf),
follow the sibling. If they disagree on *product* (live stream, Document
plane), follow architecture.md.

## This service

gRPC collector for RFC 822 .eml and Outlook .msg, projecting into the gRParse Document data plane

- **Language:** Java, plain grpc-java on virtual threads (matching grPOIc)
- **Copy from:** /work/main/grpc-services/grPOIc
- **Stack:** Jakarta Mail for `.eml`; Apache POI HSMF (`MAPIMessage`) for `.msg`. One JVM, no Python in the serving path.
- **Live stream:** EmailInfo (headers) first, then BodyPart, then Attachment, then ParseStatus.

## Definition of done (v1)

ParseEmail bidi stream, GetServiceInfo, health, reflection, in-memory .eml/.msg fixtures, read-only Docker image, byte cap. All met; keep them met.

Also: README with build/run; proto lint clean; tests that fail if someone
turns the stream back into a batch (assert an event before the input is
fully consumed, or per-item events before Complete).

Build and test:

```bash
./gradlew build                  # compiles and runs the full test suite
./gradlew test                   # 70 tests, no network
./gradlew :email-service:run     # listens on :50054
buf lint
buf breaking --against '.git#branch=main'
```

## Workspace

Checkout path: `/work/main/grpc-services/grpc-email`.
Git: `origin` = Forgejo (push `main` here). `github` = GitHub mirror.
Never merge GitHub `main`. See `docs/guidelines.md`.

gRParse wiring (`COLLECTOR_*` enum, endpoint env) is a **follow-up**.
Keep the server in this repo complete on its own first.
