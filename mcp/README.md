# MCP server

> **Status: v0.1 experimental.** The interface (tool names, arg shapes) may change in v0.x. Pin to a commit hash for stability. The bridge protocol it wraps is stable.

Model Context Protocol server that exposes any compliant bridge (see [`../docs/BRIDGE_PROTOCOL.md`](../docs/BRIDGE_PROTOCOL.md)) as MCP tools.

The reference launcher targets the Ford FDRS Layer 2 bridge. To target a different OEM bridge, copy the launcher and update the tool list.

## Tools (Ford FDRS reference)

| Tool | Maps to | Tier | Notes |
|---|---|---|---|
| `module_inventory` | `getApplicationsAndSystems` | auto | Lists vehicle modules with FDRS badge state. `freshOnly:true` requests live UDS sweep instead of cached. |
| `self_test` | `readSelfTestDTCs` | auto | UDS service 0x19 stored DTC read for a single module. `forceRefresh:true` for live read. |
| `vehicle_history` | `readVehicleHistory` | auto | CDL event log. |
| `vehicle_status` | `getVehicleModelStatus` | auto | Bundle's view of the connected vehicle. |
| `last_vehicles` | `getLastSelectedVehicles` | auto | Recent VIN list from FDRS session memory. |
| `bridge_health` | `GET /health` | auto | Bundle liveness + version. |
| `bridge_commands` | `GET /commands` | auto | Allowlisted command introspection. |
| `read_did` | `readDID` | confirm | UDS service 0x22 single DID read. Triggers real bus I/O. |
| `read_did_batch` | `readDIDBatch` | confirm | Multiple DIDs from one module in one call. |
| `stream_pid_window` | polling loop over `readDID` | confirm | Continuous DID read for a bounded window. 1-60s, 1-20Hz, max 1200 samples. |

The "tier" is advisory. Tools your AI consumer treats as requiring confirmation should match this column. The bridge itself does not enforce tier — it just runs allowlisted commands.

## Quick start

```bash
# 1. Install
cd mcp
npm install
npm run build

# 2. Make sure the bridge is running
curl http://127.0.0.1:18082/health
# expect: {"ok":true,"bundleVersion":"...","commandsExposed":[...]}

# 3. Smoke-test the MCP server
npm run smoke
# expect: tools/list response with 10 tools
```

## Connecting from Claude Code

Add to your `~/.claude/settings.json` `mcpServers`:

```json
{
  "mcpServers": {
    "fdrs": {
      "command": "node",
      "args": ["/path/to/olympus-oem-bridge/mcp/dist/bin/olympus-fdrs-layer2-mcp.js"]
    }
  }
}
```

Restart Claude Code. The 10 tools appear in any session.

## Connecting from other MCP clients

Anything that speaks MCP stdio JSON-RPC works. The MCP Inspector (`@modelcontextprotocol/inspector`) is a useful debug client.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `FDRS_LAYER2_BASE_URL` | `http://127.0.0.1:18082` | Override the bridge URL (e.g. for testing against a remote bridge). |
| `FDRS_LAYER2_TIMEOUT_MS` | `15000` | Per-bridge-call timeout. |
| `FDRS_LAYER2_DEBUG` | unset | When set, log protocol events to stderr. |

## What the MCP wrapper does NOT do

- Does not enforce tool authority — that's the consumer's responsibility (the AI agent calling the MCP).
- Does not retry failed bridge calls — exposes errors verbatim so callers can decide.
- Does not cache — every `tools/call` produces a fresh bridge dispatch.
- Does not multiplex — each MCP launcher targets one bridge URL. Multiple bridges → multiple launchers.

## v0.1 known limits

- `stream_pid_window` is synchronous — the response arrives only after the window completes. A future version will use MCP progress notifications to stream samples live.
- No `tools/subscribe` for resource-style watching (e.g. "notify me when this DID changes"). Workaround: short windows + agent-side polling.
- Schema validation is permissive — args are forwarded to the bridge mostly as-is, and the bridge's own coercer catches bad shapes. Stricter Zod schemas are planned for v0.2.
