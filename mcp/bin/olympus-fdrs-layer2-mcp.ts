#!/usr/bin/env node
/**
 * Olympus FDRS Layer 2 MCP — stdio launcher.
 *
 * Spawned as a subprocess by an MCP-aware client (Claude Code, Olympus
 * runner) via StdioClientTransport. Pipes the SDK Server's stdio onto
 * the Layer 2 bundle running inside FDRS's Felix container.
 *
 * Usage (spawn from MCP client):
 *   { command: "node", args: ["mcp/bin/olympus-fdrs-layer2-mcp.js"] }
 *
 * Usage (manual smoke test):
 *   echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
 *     node mcp/bin/olympus-fdrs-layer2-mcp.js
 *
 * Environment overrides:
 *   FDRS_LAYER2_BASE_URL   — bundle URL, default http://127.0.0.1:18082
 *   FDRS_LAYER2_TIMEOUT_MS — per-request timeout, default 15000
 *   FDRS_LAYER2_DEBUG      — when set, log protocol events to stderr
 *
 * Why stderr for logs: stdout is the MCP transport — anything written
 * there gets parsed as JSON-RPC and breaks the client. The SDK enforces
 * this internally; we mirror by logging via console.error.
 */

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { FdrsLayer2Client } from "../src/client.js";
import { buildLayer2McpServer } from "../src/mcp-server.js";

async function main(): Promise<void> {
  const baseUrl =
    process.env.FDRS_LAYER2_BASE_URL ?? "http://127.0.0.1:18082";
  const timeoutMs = parseTimeout(process.env.FDRS_LAYER2_TIMEOUT_MS);
  const debug = !!process.env.FDRS_LAYER2_DEBUG;

  const client = new FdrsLayer2Client({
    baseUrl,
    requestTimeoutMs: timeoutMs,
  });

  // Liveness probe before announcing tools — if the bundle isn't up,
  // failing fast here gives the MCP client a clean spawn-error instead
  // of every tools/call mysteriously timing out at 15s.
  try {
    const h = await client.health();
    if (debug) {
      console.error(
        `[layer2-mcp] bundle healthy: v${h.bundleVersion}, ${h.commandsExposed.length} commands exposed`,
      );
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error(
      `[layer2-mcp] bundle health probe failed at ${baseUrl}/health: ${msg}`,
    );
    console.error(
      "[layer2-mcp] verify FDRS is running and the futureai-fdrs-layer2-bridge JAR is loaded in Felix.",
    );
    process.exit(2);
  }

  const server = buildLayer2McpServer({
    client,
    logger: debug ? (line) => console.error(`[layer2-mcp] ${line}`) : undefined,
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);

  if (debug) {
    console.error(`[layer2-mcp] connected over stdio, baseUrl=${baseUrl}`);
  }
}

function parseTimeout(raw: string | undefined): number {
  if (!raw) return 15_000;
  const n = Number.parseInt(raw, 10);
  if (!Number.isFinite(n) || n <= 0) return 15_000;
  return n;
}

main().catch((err) => {
  const msg = err instanceof Error ? err.message : String(err);
  console.error(`[layer2-mcp] fatal: ${msg}`);
  process.exit(1);
});
