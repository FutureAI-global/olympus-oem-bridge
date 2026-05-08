/**
 * Type-level inventory of the Layer 2 MCP tool surface.
 *
 * Adding a new tool: add the name to LAYER2_TOOL_NAMES, then the SDK + the
 * mcp-server switch + the handler + the test fixture all become compile-
 * time-required to update. Removing a tool: same checklist in reverse.
 *
 * Tier semantics mirror the existing `mcp-server.types.ts`:
 *   - "auto"      — no confirmation needed (read-only, idempotent)
 *   - "confirm"   — requires tech approval before dispatch (state-changing
 *                    or potentially expensive, e.g. forceRefresh self-test)
 *   - "forbidden" — gated off entirely; should not appear in tools/list
 *
 * Today every tool is "auto" because the bundle's allowlist is read-only
 * commands by design. forceRefresh on self_test is "auto" at the tool
 * level; the upstream tool-authority manifest in the runner can re-tier
 * based on args (forceRefresh:true → confirm) without changing this map.
 */

export const LAYER2_TOOL_NAMES = [
  "module_inventory",
  "self_test",
  "vehicle_history",
  "vehicle_status",
  "last_vehicles",
  "bridge_health",
  "bridge_commands",
  "read_did",
  "read_did_batch",
  "stream_pid_window",
] as const;

export type Layer2McpToolName = (typeof LAYER2_TOOL_NAMES)[number];

export const LAYER2_TOOL_TIER: Record<
  Layer2McpToolName,
  "auto" | "confirm" | "forbidden"
> = {
  module_inventory: "auto",
  self_test: "auto",
  vehicle_history: "auto",
  vehicle_status: "auto",
  last_vehicles: "auto",
  bridge_health: "auto",
  bridge_commands: "auto",
  // read_did + read_did_batch trigger real module I/O over the VCM3 bus
  // per the JAR adapter docstring. The runner's tool-authority manifest
  // gates these as "confirm" — tech approves before dispatch.
  read_did: "confirm",
  read_did_batch: "confirm",
  // stream_pid_window invokes read_did N times in a tight loop. Same
  // tier as a single read_did since the side-effects are identical
  // (bus traffic) just multiplied. The runner caps total dispatch
  // count to bound wall-clock + bus airtime regardless.
  stream_pid_window: "confirm",
};

/**
 * Stream PID window safety bounds.
 *
 * The bundle spec says read_did "triggers real module I/O over the VCM3
 * bus." A naive caller can request 10 minutes at 100Hz and hold the bus
 * hostage for the duration, blocking the tech's ability to use FDRS
 * normally. These bounds exist so that even with a buggy or hostile
 * caller, stream_pid_window cannot tie up the bus for more than 60s.
 *
 * Picked empirically:
 *   maxDurationSec 60 — long enough to capture a warmup cycle or observe
 *                       a cycling fault. Beyond that, run an extended
 *                       live log via FDRS proper.
 *   maxRateHz 20      — comfortably below the bus's practical ~50Hz UDS
 *                       readDID ceiling, leaves headroom for FDRS's own
 *                       periodic broadcasts.
 *   minRateHz 1       — anything slower than once per second is just a
 *                       collection of one-shot read_did calls.
 *   maxSamples 1200   — derived: 60s * 20Hz. Caps response array size so
 *                       it can't OOM the runner.
 */
export const STREAM_PID_LIMITS = {
  maxDurationSec: 60,
  minRateHz: 1,
  maxRateHz: 20,
  maxSamples: 1200,
} as const;
