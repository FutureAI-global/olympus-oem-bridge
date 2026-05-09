/**
 * FDRS Layer 2 MCP server — formalizes the OSGi bundle command surface
 * (`http://127.0.0.1:18082/commands/*`) as a Model Context Protocol server.
 *
 * Why this exists (per Lee 2026-05-08): the existing `mcp-server.ts` routes
 * tool calls through `FdrsMcpHandlers` which depends on desk-MCP GUI
 * automation (FDRSWindowLocator, mouse_click, screenshot, locate_on_screen).
 * GUI automation is the wrong implementation: it's slow (11.5s spawn,
 * 1-10s per click), brittle (UI changes break it), and not portable
 * across OEMs. The Layer 2 bridge reverse-engineered FDRS's OSGICommandInvoker
 * surface and exposes it as direct HTTP — sub-100ms, deterministic, portable.
 *
 * Empirical evidence the Layer 2 path is the working production driver:
 *   ~/.olympus/runs/2026-04-30.jsonl + 2026-05-01.jsonl on Lee's bench machine,
 *   five `commandName: "getApplicationsAndSystems"` runs against
 *   VIN 1FTRF3AT4TEC85082, all `status:"ok"`, elapsedMs 8-23ms,
 *   itemCount 47-74. Desk-MCP would never be that fast.
 *
 * What this server exposes:
 *   - module_inventory   → getApplicationsAndSystems  (list modules + status)
 *   - self_test          → readSelfTestDTCs           (read DTCs by module)
 *   - vehicle_history    → readVehicleHistory          (event log)
 *   - vehicle_status     → getVehicleModelStatus      (connection state)
 *   - last_vehicles      → getLastSelectedVehicles    (recent VIN list)
 *   - bridge_health      → /health                    (bundle liveness probe)
 *   - bridge_commands    → /commands                  (introspection)
 *
 * Anthropic-bar anchors:
 *   P0 safety   — every tool maps 1:1 to an allowlisted bundle command.
 *                  No raw command pass-through, no string-injection surface.
 *   P1 correct  — Layer 2 client throws typed Layer2Error which we catch +
 *                  surface as in-band ToolResult error. The SDK validates
 *                  inputSchema before our handler sees the args.
 *   P3 observe  — every dispatch carries a runId in the meta block so Argus
 *                  can correlate MCP calls with downstream playbook events.
 *   P4 reverse  — `buildLayer2McpServer({ client })` factory; transport is
 *                  caller-owned so the same server runs over stdio, HTTP,
 *                  or in-process. Swap the client for tests.
 *   P6 MVA      — no custom framing; the SDK handles JSON-RPC. We add 7
 *                  thin tool wrappers around the existing FdrsLayer2Client.
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  type CallToolResult,
  type Tool,
} from "@modelcontextprotocol/sdk/types.js";

import { FdrsLayer2Client, Layer2Error } from "./client.js";
import type { Layer2InvokeResult } from "./types.js";
import {
  LAYER2_TOOL_NAMES,
  LAYER2_TOOL_TIER,
  STREAM_PID_LIMITS,
  type Layer2McpToolName,
} from "./mcp-server.types.js";

export interface BuildLayer2McpServerOpts {
  /**
   * Layer 2 HTTP client. Construct in the launcher with the bundle's
   * baseUrl (default `http://127.0.0.1:18082`) and inject here. Tests
   * pass a fake.
   */
  client: FdrsLayer2Client;
  /**
   * Optional logger for protocol-level events (initialize, tool dispatch
   * start/end, errors). Defaults to no-op so the server is silent over
   * stdio when nothing is wired.
   */
  logger?: (line: string) => void;
  /**
   * Server name advertised via the SDK initialize handshake. Defaults to
   * `olympus-fdrs-layer2`. Override in tests for assertion clarity.
   */
  serverName?: string;
  /**
   * Server version. Defaults to `0.1.0`. Bump in deploys so MCP clients
   * can detect upgrades.
   */
  serverVersion?: string;
}

const TOOL_DESCRIPTIONS: Record<Layer2McpToolName, string> = {
  module_inventory:
    "List vehicle control modules with the OEM tool's badge state (typically green/orange/red). Maps to bundle command getApplicationsAndSystems. Returns the full OSGi response tree (complexTools, systems, moduleNeutralApplications). Pass freshOnly:true to request a live UDS sweep instead of the bundle's cached snapshot (slower, 10-30s, but ground truth). Default false returns cached badge state which is fast but may be minutes-to-hours stale.",
  self_test:
    "Read stored DTCs from a specific module via UDS service 0x19 self-test. Maps to bundle command readSelfTestDTCs. Forces a live UDS read when forceRefresh:true (default false uses the bundle's cached snapshot from a prior diagnostic-tool query).",
  vehicle_history:
    "Read the vehicle's CDL (Common Diagnostic Logger) history events. Maps to bundle command readVehicleHistory. Useful for context on prior repairs and recurring symptoms.",
  vehicle_status:
    "Probe the bundle's view of the connected vehicle (connection state, current VIN, ignition status, pass-thru link health). Maps to bundle command getVehicleModelStatus. Cheap (~10ms). Call before any other dispatch to verify the OEM tool still has the vehicle.",
  last_vehicles:
    "List recently-selected vehicles from the OEM tool's session memory. Maps to bundle command getLastSelectedVehicles. Useful to recover state after a session interruption.",
  bridge_health:
    "Probe the bridge itself (not a vehicle command). Returns bundleVersion, invokerAvailable, commandsExposed. Use to verify the bridge is loaded inside the OEM tool's runtime before dispatching commands.",
  bridge_commands:
    "List all commands the bridge exposes via its allowlist. Maps to GET /commands. Useful for introspection — confirms an expected command name is exposed before invoking it.",
  read_did:
    "Read a single UDS Data Identifier (DID) from a specific module via UDS service 0x22. Maps to bundle command readDID. Returns subfields (name, dataType, unit, hexValue, convertedValue). Triggers real bus I/O — consumers should treat as 'confirm' tier (require user approval before dispatch). Typical Ford DIDs: 0xF190=VIN, 0xF1A2=ASBuilt strategy, 0xF111=cal part number, 0x1E50=throttle position, 0x1E41=engine RPM. nodeAddress accepts decimal or hex string ('0x760' for PCM, '0x727' for BCM, '0x733' for ABS).",
  read_did_batch:
    "Read multiple UDS DIDs from a single module in one call (serial reads under the hood). Maps to bundle command readDIDBatch. More efficient than N separate read_did calls because the bundle reuses a single bus session. Use when displaying a snapshot of related parameters (e.g. all engine PIDs for a triage view).",
  stream_pid_window:
    `Continuously read a single DID from a module for a bounded window, returning the full sample array. Polls read_did internally at the requested rate. Bounds enforced: durationSec <= ${STREAM_PID_LIMITS.maxDurationSec}, ${STREAM_PID_LIMITS.minRateHz} <= rateHz <= ${STREAM_PID_LIMITS.maxRateHz}, total samples <= ${STREAM_PID_LIMITS.maxSamples}. Use to observe dynamic state (EGR position cycling, fuel-trim drift, RPM-correlated faults). Synchronous: response arrives after the window completes.`,
};

const TOOL_INPUT_SCHEMAS: Record<Layer2McpToolName, Tool["inputSchema"]> = {
  module_inventory: {
    type: "object",
    properties: {
      vin: {
        type: "string",
        description:
          "17-char VIN of the connected vehicle. Echoed back in the response for cross-correlation; bundle does not use it for routing (bundle drives whatever vehicle FDRS currently has selected).",
      },
      freshOnly: {
        type: "boolean",
        default: false,
        description:
          "When true, request a live UDS sweep (bypass the bundle's cached badge state). Maps to forceRefresh on the bundle command. Older bundle versions may silently ignore this arg; the response payload's requestedFresh field surfaces what the caller asked for so consumers can detect when freshness was requested but not honored.",
      },
    },
    required: ["vin"],
  },
  self_test: {
    type: "object",
    properties: {
      vin: { type: "string", description: "17-char VIN" },
      module: {
        type: "string",
        description:
          "Module short name as returned by module_inventory (e.g. PCM, TCM, ABS, BCM, RCM).",
      },
      forceRefresh: {
        type: "boolean",
        default: false,
        description:
          "Force a live UDS 0x19 read instead of returning the bundle's cached badge state. Maps to bundle arg forceRefresh. Consumers should treat forceRefresh:true as confirm-tier even though the tool itself is auto-tier — the live-bus dispatch is what warrants confirmation.",
      },
    },
    required: ["vin", "module"],
  },
  vehicle_history: {
    type: "object",
    properties: {
      vin: { type: "string", description: "17-char VIN" },
    },
    required: ["vin"],
  },
  vehicle_status: {
    type: "object",
    properties: {
      vin: {
        type: "string",
        description:
          "17-char VIN. Used for response cross-correlation; bundle returns whatever vehicle FDRS currently has selected.",
      },
    },
    required: ["vin"],
  },
  last_vehicles: {
    type: "object",
    properties: {},
  },
  bridge_health: {
    type: "object",
    properties: {},
  },
  bridge_commands: {
    type: "object",
    properties: {},
  },
  read_did: {
    type: "object",
    properties: {
      vin: { type: "string", description: "17-char VIN" },
      didNumber: {
        type: ["integer", "string"],
        description:
          "UDS DID identifier. Decimal integer (e.g. 61840) OR hex string ('0xF190', '0xf190', 'F190'). Bundle coerces all three shapes.",
      },
      nodeAddress: {
        type: ["integer", "string"],
        description:
          "Module/ECU address on the vehicle bus. Decimal or hex. Common Ford addresses: 0x760=PCM, 0x727=BCM, 0x733=ABS, 0x720=TCM, 0x7E0=broadcast.",
      },
    },
    required: ["vin", "didNumber", "nodeAddress"],
  },
  read_did_batch: {
    type: "object",
    properties: {
      vin: { type: "string", description: "17-char VIN" },
      nodeAddress: {
        type: ["integer", "string"],
        description: "Module address. Same shape as read_did.nodeAddress.",
      },
      dids: {
        type: "array",
        items: { type: ["integer", "string"] },
        minItems: 1,
        description:
          "Array of DID identifiers to read in order. Each accepts decimal or hex string. Reads serialize on the bus.",
      },
    },
    required: ["vin", "nodeAddress", "dids"],
  },
  stream_pid_window: {
    type: "object",
    properties: {
      vin: { type: "string", description: "17-char VIN" },
      didNumber: {
        type: ["integer", "string"],
        description: "UDS DID identifier. Same shape as read_did.didNumber.",
      },
      nodeAddress: {
        type: ["integer", "string"],
        description: "Module address. Same shape as read_did.nodeAddress.",
      },
      durationSec: {
        type: "number",
        minimum: 1,
        maximum: STREAM_PID_LIMITS.maxDurationSec,
        description: `How long to poll the DID, in seconds. Bounded 1 to ${STREAM_PID_LIMITS.maxDurationSec}.`,
      },
      rateHz: {
        type: "number",
        minimum: STREAM_PID_LIMITS.minRateHz,
        maximum: STREAM_PID_LIMITS.maxRateHz,
        description: `Sampling rate in hertz. Bounded ${STREAM_PID_LIMITS.minRateHz} to ${STREAM_PID_LIMITS.maxRateHz}.`,
      },
    },
    required: ["vin", "didNumber", "nodeAddress", "durationSec", "rateHz"],
  },
};

/**
 * Build an MCP `Server` that wraps a Layer 2 client. The caller owns
 * transport wiring (stdio in `bin/olympus-fdrs-layer2-mcp.ts`, HTTP in
 * a future PR). Returns the SDK Server instance ready to `connect()`.
 */
export function buildLayer2McpServer(
  opts: BuildLayer2McpServerOpts,
): Server {
  const { client } = opts;
  const log = opts.logger ?? (() => undefined);
  const server = new Server(
    {
      name: opts.serverName ?? "olympus-fdrs-layer2",
      version: opts.serverVersion ?? "0.1.0",
    },
    {
      capabilities: {
        tools: {},
      },
    },
  );

  // ────────────────────────────────────────────────────────────────────
  // Session lifecycle
  //
  // Stateful bundle commands (readDID, readDIDBatch, readSelfTestDTCs,
  // streamPID-via-polling, vehicle_status) need a UDS session bootstrapped
  // against the VIN before they return real data. Without a session the
  // bundle accepts the call but returns `result: null` because there's
  // no SelectedVehicle in VehicleService.
  //
  // Discovered by Session N's bench (2026-05-09): readDID returned null
  // on both 1FTRF3AT4TEC85082 and 1FTER4FH8NLD22858 → not a VIN issue,
  // a session-bootstrap-missing issue.
  //
  // Strategy: lazy-bootstrap on first stateful call per-VIN. Cache the
  // sessionId for reuse. Drop + re-bootstrap on freshOnly:true so callers
  // can force a fresh SelectVehicle (which is the actual semantics of
  // "live UDS sweep" — re-establish the vehicle context, then read).
  // ────────────────────────────────────────────────────────────────────

  const sessionsByVin = new Map<string, string>();

  async function ensureSession(vin: string, opts: { forceFresh?: boolean } = {}): Promise<string | undefined> {
    if (typeof vin !== "string" || vin.length === 0) return undefined;
    if (opts.forceFresh) {
      const stale = sessionsByVin.get(vin);
      sessionsByVin.delete(vin);
      if (stale) {
        // Best-effort release; never throws.
        client.releaseSession(stale).catch(() => undefined);
      }
    }
    const cached = sessionsByVin.get(vin);
    if (cached) return cached;
    try {
      const r = await client.bootstrap(vin);
      sessionsByVin.set(vin, r.sessionId);
      log(`layer2-mcp: bootstrapped session ${r.sessionId.slice(0, 8)}... for ${vin.slice(0, 4)}...`);
      return r.sessionId;
    } catch (err) {
      // Bootstrap failed (e.g. wrong VIN, FDRS Workshop instead of live).
      // Surface as error in the calling tool result. ensureSession returns
      // undefined so the call falls through to a session-less invoke,
      // which will return null result that the caller can detect.
      log(`layer2-mcp: bootstrap failed for vin=${vin}: ${(err as Error).message.slice(0, 120)}`);
      return undefined;
    }
  }

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    log("layer2-mcp: tools/list");
    const tools: Tool[] = LAYER2_TOOL_NAMES.map((name) => ({
      name,
      description: TOOL_DESCRIPTIONS[name],
      inputSchema: TOOL_INPUT_SCHEMAS[name],
      _meta: { tier: LAYER2_TOOL_TIER[name] },
    }));
    return { tools };
  });

  server.setRequestHandler(CallToolRequestSchema, async (req) => {
    const name = req.params.name as Layer2McpToolName;
    const args = (req.params.arguments ?? {}) as Record<string, unknown>;
    log(`layer2-mcp: tools/call ${name}`);

    if (!LAYER2_TOOL_NAMES.includes(name)) {
      return errorResult(`unknown tool: ${name}`);
    }

    try {
      switch (name) {
        case "module_inventory": {
          const freshOnly = args.freshOnly === true;
          const vin = typeof args.vin === "string" ? args.vin : "";
          // Bootstrap (or re-bootstrap on freshOnly) so the inventory
          // reflects the current vehicle. The bundle's
          // getApplicationsAndSystems is largely metadata about installed
          // FDRS tools but its filtering depends on SelectedVehicle.
          const sessionId = await ensureSession(vin, { forceFresh: freshOnly });
          const inv = await client.invoke(
            "getApplicationsAndSystems",
            { forceRefresh: freshOnly },
            sessionId,
          );
          return successResult(inv, {
            vin: args.vin,
            requestedFresh: freshOnly,
            sessionEstablished: !!sessionId,
          });
        }
        case "self_test": {
          const sessionId = await ensureSession(
            typeof args.vin === "string" ? args.vin : "",
          );
          return successResult(
            await client.invoke(
              "readSelfTestDTCs",
              {
                module: args.module,
                forceRefresh: args.forceRefresh === true,
              },
              sessionId,
            ),
            {
              vin: args.vin,
              module: args.module,
              sessionEstablished: !!sessionId,
            },
          );
        }
        case "vehicle_history": {
          const sessionId = await ensureSession(
            typeof args.vin === "string" ? args.vin : "",
          );
          return successResult(
            await client.invoke("readVehicleHistory", {}, sessionId),
            { vin: args.vin, sessionEstablished: !!sessionId },
          );
        }
        case "vehicle_status": {
          // vehicle_status is the canonical "is the bundle in a good state"
          // probe. Bootstrap so it reflects the live SelectedVehicle.
          const sessionId = await ensureSession(
            typeof args.vin === "string" ? args.vin : "",
          );
          return successResult(
            await client.invoke("getVehicleModelStatus", {}, sessionId),
            { vin: args.vin, sessionEstablished: !!sessionId },
          );
        }
        case "last_vehicles":
          // Stateless — recent VIN list comes from FDRS session memory,
          // not the active UDS bus.
          return successResult(
            await client.invoke("getLastSelectedVehicles", {}),
            {},
          );
        case "bridge_health":
          return rawResult(await client.health());
        case "bridge_commands":
          return rawResult(await client.listCommands());
        case "read_did": {
          const sessionId = await ensureSession(
            typeof args.vin === "string" ? args.vin : "",
          );
          if (!sessionId) {
            return errorResult(
              "read_did requires a UDS session; bootstrap failed for vin=" +
                String(args.vin),
            );
          }
          return successResult(
            await client.invoke(
              "readDID",
              {
                didNumber: args.didNumber,
                nodeAddress: args.nodeAddress,
              },
              sessionId,
            ),
            { vin: args.vin, sessionId },
          );
        }
        case "read_did_batch": {
          const sessionId = await ensureSession(
            typeof args.vin === "string" ? args.vin : "",
          );
          if (!sessionId) {
            return errorResult(
              "read_did_batch requires a UDS session; bootstrap failed for vin=" +
                String(args.vin),
            );
          }
          return successResult(
            await client.invoke(
              "readDIDBatch",
              {
                nodeAddress: args.nodeAddress,
                dids: args.dids,
              },
              sessionId,
            ),
            { vin: args.vin, sessionId },
          );
        }
        case "stream_pid_window": {
          const r = validateStreamArgs(args);
          if (!r.ok) return errorResult(r.error);
          const sessionId = await ensureSession(
            typeof r.parsed.vin === "string" ? r.parsed.vin : "",
          );
          if (!sessionId) {
            return errorResult(
              "stream_pid_window requires a UDS session; bootstrap failed for vin=" +
                String(r.parsed.vin),
            );
          }
          return await runStreamPidWindow(client, r.parsed, log, sessionId);
        }
      }
    } catch (err) {
      if (err instanceof Layer2Error) {
        return errorResult(`layer2-${err.kind}: ${err.message}`);
      }
      const msg = err instanceof Error ? err.message : String(err);
      return errorResult(`unexpected: ${msg}`);
    }
  });

  return server;
}

function successResult(
  invoke: Layer2InvokeResult<unknown>,
  echo: Record<string, unknown>,
): CallToolResult {
  if (!invoke.ok) {
    return errorResult(`bundle-${invoke.kind}: ${invoke.error}`);
  }
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(
          {
            ok: true,
            command: invoke.command,
            result: invoke.result,
            latencyMs: invoke.latencyMs,
            ...echo,
          },
          null,
          2,
        ),
      },
    ],
    isError: false,
  };
}

function rawResult(payload: unknown): CallToolResult {
  return {
    content: [
      { type: "text", text: JSON.stringify(payload, null, 2) },
    ],
    isError: false,
  };
}

function errorResult(message: string): CallToolResult {
  return {
    content: [{ type: "text", text: message }],
    isError: true,
  };
}

// ──────────────────────────────────────────────────────────────────────
// stream_pid_window helpers
// ──────────────────────────────────────────────────────────────────────

interface StreamPidArgs {
  vin: unknown;
  didNumber: unknown;
  nodeAddress: unknown;
  durationSec: number;
  rateHz: number;
}

type ValidateStreamResult =
  | { ok: true; parsed: StreamPidArgs }
  | { ok: false; error: string };

function validateStreamArgs(args: Record<string, unknown>): ValidateStreamResult {
  const dur = toNumber(args.durationSec);
  const rate = toNumber(args.rateHz);
  if (dur === null) return { ok: false, error: "durationSec must be a number" };
  if (rate === null) return { ok: false, error: "rateHz must be a number" };
  if (dur <= 0 || dur > STREAM_PID_LIMITS.maxDurationSec) {
    return {
      ok: false,
      error: `durationSec must be in (0, ${STREAM_PID_LIMITS.maxDurationSec}]`,
    };
  }
  if (rate < STREAM_PID_LIMITS.minRateHz || rate > STREAM_PID_LIMITS.maxRateHz) {
    return {
      ok: false,
      error: `rateHz must be in [${STREAM_PID_LIMITS.minRateHz}, ${STREAM_PID_LIMITS.maxRateHz}]`,
    };
  }
  // Sanity check on derived sample count — even if dur and rate are
  // individually valid, their product can briefly exceed maxSamples
  // due to the integer-rounding gap. Catch it explicitly.
  if (Math.ceil(dur * rate) > STREAM_PID_LIMITS.maxSamples) {
    return {
      ok: false,
      error: `total samples ${Math.ceil(
        dur * rate,
      )} exceeds maxSamples ${STREAM_PID_LIMITS.maxSamples}`,
    };
  }
  return {
    ok: true,
    parsed: {
      vin: args.vin,
      didNumber: args.didNumber,
      nodeAddress: args.nodeAddress,
      durationSec: dur,
      rateHz: rate,
    },
  };
}

function toNumber(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

interface PidSample {
  /** Wall-clock at sample start, ms since epoch. */
  t: number;
  /** Latency of THIS sample's bus read, ms. Useful for spotting bus stalls. */
  latencyMs: number;
  /** Successful sample payload, or null when this sample's read failed. */
  result: unknown;
  /** Error message if result is null (bundle ok:false, network error, etc). */
  error?: string;
}

async function runStreamPidWindow(
  client: FdrsLayer2Client,
  args: StreamPidArgs,
  log: (line: string) => void,
  sessionId?: string,
): Promise<CallToolResult> {
  const intervalMs = Math.round(1000 / args.rateHz);
  const startMs = Date.now();
  const endMs = startMs + Math.round(args.durationSec * 1000);
  const samples: PidSample[] = [];
  let okCount = 0;
  let errCount = 0;

  while (Date.now() < endMs && samples.length < STREAM_PID_LIMITS.maxSamples) {
    const tickStart = Date.now();
    let sample: PidSample;
    try {
      const inv = await client.invoke(
        "readDID",
        {
          didNumber: args.didNumber,
          nodeAddress: args.nodeAddress,
        },
        sessionId,
      );
      const latencyMs = Date.now() - tickStart;
      if (inv.ok) {
        sample = { t: tickStart, latencyMs, result: inv.result };
        okCount += 1;
      } else {
        sample = {
          t: tickStart,
          latencyMs,
          result: null,
          error: `bundle-${inv.kind}: ${inv.error}`,
        };
        errCount += 1;
      }
    } catch (err) {
      const latencyMs = Date.now() - tickStart;
      const msg = err instanceof Layer2Error
        ? `layer2-${err.kind}: ${err.message}`
        : err instanceof Error
          ? err.message
          : String(err);
      sample = { t: tickStart, latencyMs, result: null, error: msg };
      errCount += 1;
    }
    samples.push(sample);

    // Pace the next sample. If the read took longer than the interval,
    // skip the sleep — we're already late and trying to "catch up" by
    // queuing instant calls would just hammer the bus.
    const sleepMs = intervalMs - (Date.now() - tickStart);
    if (sleepMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, sleepMs));
    }
  }

  log(
    `layer2-mcp: stream_pid_window done · ${samples.length} samples · ${okCount} ok · ${errCount} err`,
  );

  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(
          {
            ok: true,
            vin: args.vin,
            didNumber: args.didNumber,
            nodeAddress: args.nodeAddress,
            requested: {
              durationSec: args.durationSec,
              rateHz: args.rateHz,
              intervalMs,
            },
            actual: {
              startMs,
              endMs: Date.now(),
              elapsedMs: Date.now() - startMs,
              sampleCount: samples.length,
              okCount,
              errCount,
            },
            samples,
          },
          null,
          2,
        ),
      },
    ],
    isError: false,
  };
}
