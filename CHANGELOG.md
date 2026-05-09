# Changelog

## Unreleased

### Added
- **OSGi-Java bridge template** (`bridges/_template/osgi-java/`) — Activator + CommandAdapter + BundleHttpServer skeletons + MANIFEST template for OEM tools running on Felix/Equinox. Pairs with the existing Python skeleton in `bridges/_template/bridge.py` for tools that can host a sidecar process.
- **Real jest tests for the MCP server** (`mcp/__tests__/mcp-server.test.ts`, 26 tests) — covers tools/list, all 10 tools, session-bootstrap lifecycle, error paths, and stream-PID polling cadence. CI now runs `npm test` between tsc and build.
- **Receiver-pattern recovery in `bridges/ford-fdrs/.../ReadDIDAdapter.java`** — handles Ford's auto-receiver result-delivery pattern when `inv.invoke()` returns null. Layered fallback: walkBean direct → cmd-state scan → structured diagnostic dump. Documented in `docs/ADDING_AN_OEM.md` so any OEM bridge author working on an OTX-derived stack avoids the same trap.
- **Session lifecycle in `mcp/src/mcp-server.ts`** — lazy bootstrap per VIN, drop+rebootstrap on `freshOnly:true`, sessionId threaded through every stateful invoke. Stateless tools (bridge_health, bridge_commands, last_vehicles) skip bootstrap.
- **Bridge protocol docs** — `docs/BRIDGE_PROTOCOL.md` (HTTP wire format), `docs/ARCHITECTURE.md` (design rationale + threat model), `docs/ADDING_AN_OEM.md` (contributor walkthrough with receiver-pattern gotcha).
- **SECURITY.md, CONTRIBUTING.md** — disclosure policy, threat model, PR review criteria.

### Fixed
- `bridges/ford-fdrs/scripts/build.sh` — `compgen -G` array-split broke on Windows install paths with spaces (`Program Files (x86)`). Replaced with `mapfile -t` so paths with spaces survive intact. Caught on McGraw bench.
- `bridges/ford-fdrs/scripts/deploy.sh` — `$USER` is unset under Git Bash on Windows, tripping `set -u`. Added `: "${USER:=$(whoami)}"` guard near the top.
- `mcp/src/client.ts` — added explicit `.js` extension on relative import for NodeNext module resolution. CI couldn't compile without it.

### Changed
- `mcp/src/mcp-server.ts` — tool descriptions stripped of Olympus-product-internal jargon (`MUST-31` references). Reads as generic OEM-bridge documentation.
- `bridges/ford-fdrs/README.md` — rewritten clean. Removed `~/.olympus/...` paths and stale Phase 1-4 status table. Anchors on the current 18-command allowlist + a 24ms bench latency baseline.

### Verified empirically
- Bundle 0.2.5 alive on dev box: `bridge_health` returns `bundleVersion: 0.2.5, invokerAvailable: true, 18 commands exposed`.
- `module_inventory` against test VIN: 24ms cached, 310ms freshOnly (with session-bootstrap overhead).
- JAR build: 58,142 bytes (matches McGraw bench build byte-for-byte). Bytecode confirms `findReceiverValue`, `cmdGetters`, `cmdReceiverType`, `sourceWasNull`, `LAST_CMD`, `reflectGetters`, `getReceiverType` symbols all present in `ReadDIDAdapter.class`.

## v0.1 (2026-05-08) — initial public mirror

### Added
- Initial open-source release of the Ford-FDRS bridge + MCP wrapper.
- 37 OSGi bundle source files at `bridges/ford-fdrs/`.
- 5 MCP server files at `mcp/` (server, types, client, types, stdio launcher).
- Apache-2.0 license.

Origin: extracted from FutureAI's Olympus diagnostic AI product. The Ford FDRS bridge had been validated in production over 14+ days on real Ford diagnostic sessions before the public release.

The proprietary AI layer (constitution, playbooks, runner orchestration, billing) lives in a separate private repo. This repo is just the bridge plumbing.
