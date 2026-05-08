# Contributing

Welcome. The most valuable contributions are:

1. **New OEM bridges** — the goal of this repo. See [`docs/ADDING_AN_OEM.md`](./docs/ADDING_AN_OEM.md) for the walkthrough.
2. **New commands on existing bridges** — extending the allowlist with read-only commands, with safety justification.
3. **MCP layer improvements** — better progress notifications, resource subscriptions, stricter input validation.
4. **Documentation** — especially: clarifying the bridge protocol for OEM tools we don't yet have a reference impl for.

## Before opening a PR

- Read [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) and [`docs/BRIDGE_PROTOCOL.md`](./docs/BRIDGE_PROTOCOL.md). The bridge pattern and HTTP wire shape are stable; consumers depend on them.
- Read [`SECURITY.md`](./SECURITY.md). Anything that affects allowlist enforcement, argument validation, or local-only binding is security-sensitive.
- Make sure CI passes locally (`cd mcp && npm install && npm run build && npx tsc --noEmit`).

## PR review criteria

Every PR is reviewed against these criteria:

1. **Allowlist discipline.** A new command added to an existing bridge requires:
   - One-line justification in the PR description (why is this command safe and useful?)
   - Confirmation it's read-only OR a written safety case if it's write-capable
   - One real-vehicle smoke test result in the PR body
2. **No DRM/licensing bypass.** The bridge calls APIs the OEM tool already exposes internally. Anything that defeats encryption, signing, or licensing checks gets rejected.
3. **Localhost binding preserved.** Any change to the bridge's HTTP server must keep `127.0.0.1` binding. No `0.0.0.0`, no Unix sockets in shared paths.
4. **No new external deps without justification.** Each external dep is a supply-chain attack surface. The reference Ford bridge uses zero external Java deps; the MCP uses only `@modelcontextprotocol/sdk`. Stay tight.
5. **Tests pass locally + CI is green.**

## Style

- TypeScript (MCP): match the existing files. NodeNext modules. Strict mode. Explicit `.js` extensions on relative imports.
- Java (bridges): match the existing OSGi bundle. Java 17 source level (FDRS ships JDK 17). No Maven/Gradle unless the OEM toolchain forces it.
- Markdown: GitHub-flavored. Code blocks with language hints.

## What gets merged fast

- Bridges for OEMs not yet covered, with at least one read-only command and a real-vehicle smoke
- Doc improvements that close gaps a contributor explicitly hit (PR description: "I tried to do X and got stuck because Y")
- CI improvements
- Test coverage for existing untested code paths

## What gets bounced back

- New commands without safety justification
- Bridges that bypass OEM licensing
- PRs that bind the HTTP server to `0.0.0.0` "for convenience"
- Adding external deps without explaining why the stdlib doesn't suffice
- Changes to the [`bridge protocol`](./docs/BRIDGE_PROTOCOL.md) without a public RFC

## Apache-2.0

By contributing, you agree your code is licensed Apache-2.0 (see [`LICENSE`](./LICENSE)). No CLA required; the Apache-2.0 license itself includes the necessary contributor grant.

## Getting help

Open an issue with the `question` label. For security-related questions, see [`SECURITY.md`](./SECURITY.md).
