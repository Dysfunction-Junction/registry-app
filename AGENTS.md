# Agent index

This file is a map. It names checkouts, ports, and the file to open. It does not describe behavior. If a line here and the code disagree, the code wins; then correct this file's path or port.

The launch table is copied from `sovereign-exocortex/apps/daemon/src/mishmash-nodes.ts`. That file is the routing contract. Checkouts sit next to each other. The launcher treats a directory as the repos root when it contains `hermes-api`, `autonomous-triad`, and `pieces-for-all`.

## Family

| Launch id | Directory | Port | Role in the launch table | Mesh entry |
|---|---|---|---|---|
| `exocortexd` | `sovereign-exocortex` | 8788 | gateway | `apps/daemon/src/server.ts` |
| `hermes-api` | `hermes-api` | 3000 | device and phone tasks | `mishmash/delegate.ts` |
| `autonomous-triad` | `autonomous-triad` | 8789 | intent and review | `triad.server` |
| `pieces-for-all` | `pieces-for-all` | 39400 | local memory gateway | `src/server.ts` |
| `agent-mesh-mcp` | `agent-mesh-mcp` | 39401 | local MCP switchboard | `src/mishmash-delegate.ts` |
| `pattern-analyzer` | `pattern-analyzer` | 39402 | pattern lifecycle ranking | `src/mishmash-delegate.ts` |
| `registry-app` | `registry-app` | 39403 | SecondGuess staging | `mishmash/delegate.ts` |
| `gut-instinct` | `Gut-Instinct` | 39404 | on-device coach contract | `mishmash/delegate.mjs` |
| `tool-registry-heartbeat` | `tool-registry-heartbeat` | 39405 | scheduled tool-inventory tasks | `mishmash/delegate.mjs` |

The home proxy (often `:8787`) and PiecesOS (`:39300`) are separate local processes. They are not checkouts in this table.

## This checkout

Mesh launch id `registry-app`, port 39403, entry [`mishmash/delegate.ts`](mishmash/delegate.ts). npm package name is `secondguess`. This is the Expo / Firebase app, not the pattern-math package.

| Path | Open when the question is about |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | component map. The handoff section is dated 2026-09-08; read the code for anything after that date. |
| [`App.tsx`](App.tsx) | Expo entry |
| [`functions/src/index.ts`](functions/src/index.ts) | Cloud Functions entry |
| [`firestore.rules`](firestore.rules) | data access rules |
| [`native-usage-collector/`](native-usage-collector) | Android usage collector |
| [`contextual-coach/`](contextual-coach) | coach sources that live in this repo |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | contribution steps |
| [`mishmash/delegate.ts`](mishmash/delegate.ts) | loopback delegate the exocortex gateway launches |

The lifecycle engine is the sibling checkout `pattern-analyzer` (`@registry/pattern-analyzer`). The standalone Kotlin coach with the 13 invariants is `Gut-Instinct` (`Gut-Instinct/CLAUDE.md`). Do not merge those rules into this app unless the file you are editing already states them.
