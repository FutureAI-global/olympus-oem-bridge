# Architecture

## Why a bridge instead of GUI automation

OEM diagnostic tools have command surfaces that are perfectly programmable internally — they have to be, because the tool's own UI calls them. The challenge is that those internals are not exposed externally. The standard ways to drive an OEM tool from the outside are:

1. **GUI automation** (mouse/keyboard/screenshot via tools like AutoIt, pywinauto, desk-mcp). Slow (1-10s per click), brittle (UI changes break it), per-resolution-and-locale, poor at concurrent calls.
2. **Network proxying** (intercept the OEM tool's calls to its server). Tool-version-specific, breaks on TLS pinning, often illegal under EULA.
3. **Bridge** (load a small adapter into the OEM tool's own runtime, expose its existing internal command bus). Fast (sub-100ms), deterministic, version-independent, and respects the EULA as much as the OEM tool's own internal usage of those commands.

This repo takes path 3.

## The bridge pattern

For a given OEM tool, the bridge is a small piece of code (typically 10-50 source files) that:

1. **Loads into the tool's runtime.** For Ford FDRS, this is an OSGi bundle that drops into Felix's `bundle/` directory and is auto-deployed at FDRS launch. For .NET-based tools, it would be an assembly loaded via `AppDomain.Load()`. For Electron-based tools, it would be a JS module loaded into the renderer.
2. **Discovers the tool's existing command surface.** For Ford FDRS, the OSGi service registry exposes `OSGICommandInvoker` which dispatches ~96 named commands. For .NET tools, this might be a `ServiceLocator.GetService<ICommandBus>()`. The discovery is OEM-specific; the result is a generic command-dispatch interface.
3. **Exposes a small HTTP server on `127.0.0.1:18082`** with three endpoints:
   - `GET /health` — liveness probe
   - `GET /commands` — list of allowlisted command names
   - `POST /commands/<name>` — invoke a single command with JSON args
4. **Runs an allowlist** so external callers cannot invoke arbitrary commands. The allowlist starts with read-only commands (module inventory, DTC reads, history). Every addition is a PR that documents why the new command is safe.

The bridge does not:
- Modify the OEM tool's behavior in any way the tool wouldn't do itself
- Reverse-engineer the OEM tool's protected/encrypted functionality
- Bypass licensing checks (the OEM tool still has to be running and licensed)
- Persist state between calls (each command invocation is independent)

## The MCP layer

The bridge speaks HTTP. The MCP server in this repo wraps that HTTP surface as a Model Context Protocol stdio server. This means:

- AI agents (Claude Code, ChatGPT-via-an-MCP-bridge, Cursor, your own) can consume the bridge through a standard interface they already speak.
- The MCP server can be reused across OEMs — the bridge URL is configurable, and the tool list is the same wire shape regardless of which OEM is on the other side.
- Tool-authority gates (which commands need user confirmation) live in the MCP layer, not duplicated in every consumer.

## Why localhost-only HTTP

The bridge listens only on `127.0.0.1`. Three reasons:
1. **Threat model.** Anything bound to a public interface is exposed to the network. Diagnostic commands can read VINs, DTCs, mileage, fuel level — sensitive data from a vehicle the tool is connected to. Localhost binding scopes the surface to processes already running on the technician's machine.
2. **No auth required.** Localhost-only means anything that can reach the port already has local-machine access. We don't ship secrets in the bridge or rotate keys.
3. **Simplicity.** Vehicle bus operations are not the kind of thing you want behind a load balancer or an API gateway. One process, one port, one machine.

The MCP server adds a stdio transport on top, which is even more locked-down: the only consumer is the AI client that spawned the MCP subprocess.

## Why the MCP wraps HTTP instead of speaking directly

The bridge could expose JSON-RPC stdio directly and skip HTTP. Two reasons it doesn't:

1. **Tools other than AI agents need to call the bridge.** Test scripts, CLI debug tools, the technician's own curl invocations. HTTP is universal; stdio is not.
2. **The bridge runs inside the OEM tool's process.** Adding stdio there means hijacking stdin/stdout, which fights for the OEM tool's logging. HTTP is process-internal in a way that doesn't disturb anything else.

The MCP wrapping happens in a separate Node.js process that translates between stdio JSON-RPC (the AI agent's preferred surface) and HTTP (the bridge's surface).

## State separation

Each layer is stateless except where the OEM tool itself holds state:

- **Vehicle bus state** — held by the OEM tool (which vehicle is selected, what the most-recent module inventory was, whether VCM3 is paired). Bridge is read-only.
- **Bridge state** — none. Each command invocation is independent.
- **MCP state** — minimal. Only the connection lifecycle (spawn, initialize, shutdown).
- **Agent state** — held by the agent. The agent decides which tools to call, in what order, with what args.

This means restarting any layer recovers cleanly without coordination.

## Performance

Empirical measurements on Ford FDRS (production):

| Operation | Latency | Notes |
|---|---|---|
| `getApplicationsAndSystems` (47-114 modules) | 8-23ms | cached badge state |
| `readSelfTestDTCs` (single module, cached) | 10-50ms | bundle's snapshot |
| `readSelfTestDTCs` (single module, forceRefresh) | 1-30s | live UDS 0x19 sweep |
| `readDID` (single DID) | 50-150ms | live UDS 0x22 read |
| MCP framing overhead per call | +5-15ms | stdio JSON-RPC |

The UDS-bus operations are bus-bound, not bridge-bound. The bridge itself adds <5ms over a direct OSGi service-ref call.
