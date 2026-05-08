# Bridge HTTP protocol

The wire protocol every bridge must implement. The MCP layer in `mcp/` only knows about this protocol; it has no knowledge of any specific OEM tool.

## Endpoints

### `GET /health`

Liveness probe + introspection. Always returns 200 if the bridge is running.

```json
{
  "ok": true,
  "bundleVersion": "0.1.0",
  "invokerAvailable": true,
  "commandsExposed": ["getApplicationsAndSystems", "readSelfTestDTCs", "readVehicleHistory"]
}
```

| Field | Type | Notes |
|---|---|---|
| `ok` | `true` | Always true. Errors come back as HTTP 500 with a different body. |
| `bundleVersion` | string | Semver of the bridge artifact. The MCP server logs this on connect. |
| `invokerAvailable` | bool | Whether the bridge could resolve the OEM tool's command-dispatch service. False indicates a degraded state — bridge loaded but cannot invoke commands. |
| `commandsExposed` | string[] | Array of command names currently in the bridge's allowlist. Used for `tools/list`-style introspection. |

### `GET /commands`

List all allowlisted commands with their argument shapes.

```json
{
  "ok": true,
  "commands": [
    {
      "publicName": "readDID",
      "commandClass": "com.ford.otx.services.vehicle.comms.command.ReadDID",
      "takesArgs": {
        "didNumber": "int (required) — UDS DID identifier. Decimal or hex string.",
        "nodeAddress": "int (required) — Module/ECU address on the vehicle bus."
      }
    }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `publicName` | string | The name external callers use in `POST /commands/<name>`. |
| `commandClass` | string | OEM-tool-internal class/identifier the bridge dispatches to. Informational, useful for debugging. |
| `takesArgs` | object | Map of arg name → human-readable description. Free-form text — JSON-Schema validation happens elsewhere. |

### `POST /commands/<name>`

Invoke a single allowlisted command with JSON args.

Request:
```http
POST /commands/readDID HTTP/1.1
Content-Type: application/json

{
  "args": {
    "didNumber": "0xF190",
    "nodeAddress": "0x760"
  }
}
```

Success response (200):
```json
{
  "ok": true,
  "command": "readDID",
  "result": {
    "id": 61840,
    "didType": "vehicle-info",
    "subfields": [
      { "id": "VIN", "name": "VIN", "convertedValue": "1FTRF3AT4TEC85082", "hexValue": "..." }
    ]
  },
  "latencyMs": 87
}
```

Error response (200, ok:false):
```json
{
  "ok": false,
  "command": "readDID",
  "error": "command not in allowlist",
  "kind": "allowlist_miss",
  "latencyMs": 1
}
```

Note: errors are returned as HTTP 200 with `ok: false`, NOT as HTTP 4xx/5xx. This is deliberate — it lets the MCP layer distinguish between transport failure (HTTP 5xx) and bridge-rejected calls (HTTP 200, ok:false).

### Error kinds

| `kind` | Meaning |
|---|---|
| `not_found` | Command name doesn't exist in any registry. |
| `allowlist_miss` | Command exists but is not in the bridge's allowlist. |
| `invoker_unavailable` | The OEM tool's command-dispatch service is not currently resolvable (tool may be starting up or in a degraded state). |
| `bad_request` | HTTP-level malformed request (missing body, wrong content-type). |
| `bad_args` | Args object is missing or has wrong shape. |
| `invocation_error` | The command was dispatched but the OEM tool returned an error or threw. |
| `serialize_error` | The command succeeded but the bridge couldn't serialize the result to JSON. |
| `internal_error` | Something else went wrong inside the bridge. Bug. |

## Versioning

The protocol is versioned by `bundleVersion` in `/health`. MCP servers can branch on the version if a new protocol field is added. Breaking changes to existing fields require a major version bump (e.g. `1.x.y` → `2.x.y`) and the MCP server should refuse to connect to a bundle whose major version it doesn't understand.

Today (v0.1) there is one version. Future protocol changes will go through a public RFC process before landing.

## Threat model + safety

The bridge is localhost-only (binds `127.0.0.1:18082`). Anything that can reach it already has local-machine access and can read the same vehicle data the OEM tool reads.

The allowlist is the primary safety boundary. New commands need a PR with safety justification:
- Read-only commands (no bus writes, no state mutation) can be added with brief justification.
- Write-capable commands (active tests, calibration writes, key learn) require an explicit safety case with rollback semantics.
- Commands that interact with safety-critical systems (powertrain control mutations, brake/steering interventions) are off-limits without OEM partnership.

The reference Ford bridge ships with read-only commands only.
