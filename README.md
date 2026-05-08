# olympus-oem-bridge

Open-source bridges + MCP wrappers for vehicle OEM diagnostic tools.

OEM diagnostic software (Ford FDRS, Porsche PIWIS, GM GDS2, Toyota Techstream, Honda HDS, Stellantis wiTECH, BMW ISTA, Mercedes XENTRY, etc.) ships with rich command surfaces buried inside proprietary clients. This repo contains:

1. **Bridges** — small adapters that load into the OEM tool's own runtime (e.g. an OSGi bundle inside FDRS's Felix container) and expose the tool's existing command bus over a simple HTTP protocol.
2. **MCP servers** — Model Context Protocol servers that wrap each bridge so any AI agent (Claude Code, ChatGPT, Cursor, etc.) can drive the OEM tool through a standard stdio JSON-RPC interface.

The result: a Ford technician can hand a VIN to an AI assistant, and the assistant can read the same module inventory, DTCs, and live PIDs that FDRS reads — without GUI automation, without screen-scraping, without inventing a new protocol. Just calling commands the OEM tool already exposes internally.

## Status

| Bridge | Status | Validation |
|---|---|---|
| **Ford FDRS** | production-proven | 14+ days in production on real Ford diagnostic sessions; sub-100ms per command |
| _template_ | scaffold for contributors | starting point for a new OEM bridge |

Other OEMs welcome — see [`docs/ADDING_AN_OEM.md`](./docs/ADDING_AN_OEM.md).

## Quick start (Ford FDRS)

Requires FDRS installed on a Windows machine.

```bash
# 1. Build the OSGi bundle
cd bridges/ford-fdrs
./scripts/build.sh
# -> build/jar/futureai-fdrs-layer2-bridge-X.Y.Z.jar

# 2. Deploy into FDRS's Felix container
./scripts/deploy.sh
# Restart FDRS. Bundle activates on launch.

# 3. Start the MCP server
cd ../../mcp
npm install
node bin/olympus-fdrs-layer2-mcp.js
# Probes bundle/health on startup, exits if not loaded.

# 4. Connect any MCP client
# (Claude Code, the @modelcontextprotocol/inspector, your own client)
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  node bin/olympus-fdrs-layer2-mcp.js
```

See [`bridges/ford-fdrs/README.md`](./bridges/ford-fdrs/README.md) for the full bundle build/deploy instructions and [`mcp/README.md`](./mcp/README.md) for the MCP server interface.

## Architecture

```
┌─────────────────────────┐
│   AI agent (Claude,     │
│   GPT, your own)        │
└──────────┬──────────────┘
           │ MCP stdio JSON-RPC
           ▼
┌─────────────────────────┐
│   MCP server            │  mcp/   (TypeScript, Node.js)
│   (this repo)           │
└──────────┬──────────────┘
           │ HTTP
           ▼
┌─────────────────────────┐
│   Bridge HTTP server    │  bridges/<oem>/   (lives inside the OEM tool's runtime)
│   :18082                │
└──────────┬──────────────┘
           │ JNI / OSGi service ref / native API
           ▼
┌─────────────────────────┐
│   OEM tool's existing   │
│   command bus           │
│   (proprietary)         │
└──────────┬──────────────┘
           │ J2534 / VCM / pass-thru cable
           ▼
┌─────────────────────────┐
│   Vehicle (UDS over CAN)│
└─────────────────────────┘
```

The bridge is the only OEM-specific code. The MCP layer is generic — it just speaks HTTP to whatever bridge is on `127.0.0.1:18082`.

See [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) for the full design rationale and [`docs/BRIDGE_PROTOCOL.md`](./docs/BRIDGE_PROTOCOL.md) for the HTTP wire protocol.

## What this repo is NOT

- Not a complete diagnostic AI. It's the plumbing under one. The diagnostic intelligence layer (the AI's reasoning, repair playbooks, narration, billing) lives in the proprietary product that uses this bridge.
- Not a replacement for the OEM tool itself. The OEM tool still has to be installed and licensed; the bridge runs inside it.
- Not a way to bypass OEM licensing or DRM. The bridge calls public-or-internal APIs the tool already exposes; it does not crack, modify, or unlock any protected functionality.

## Why open-source

OEM coverage is a network-effect problem. Every OEM needs its own bridge because each tool has a different runtime (Felix/OSGi for Ford FDRS, .NET for some, Java Swing for others). A single team cannot ship 12+ bridges quickly. Open-sourcing the pattern lets independent technicians and OEM-tool reverse-engineering communities (FORScan, AlfaOBD, BimmerLink, etc.) contribute bridges directly. The MCP layer means any contributed bridge is immediately usable by any AI agent.

If you write a bridge for an OEM not yet covered, please open a PR — see [`docs/ADDING_AN_OEM.md`](./docs/ADDING_AN_OEM.md).

## License

[Apache-2.0](./LICENSE). Use it, fork it, ship it commercially. The only ask: contribute back the bridges so the OEM-coverage matrix grows for everyone.

## Origin

Extracted from FutureAI's Olympus diagnostic AI product. The Ford FDRS bridge was built and validated in production over 14+ days on real Ford diagnostic sessions before being open-sourced.

The proprietary AI layer (constitution, playbooks, runner orchestration, billing) lives in a separate private repo. This repo is just the plumbing.
