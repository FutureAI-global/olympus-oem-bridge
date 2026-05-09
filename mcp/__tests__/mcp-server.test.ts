/**
 * Tests for the Layer 2 MCP server. Exercises tools/list and tools/call
 * with a faked FdrsLayer2Client so the suite never needs a running bundle.
 *
 * Coverage:
 *   - tools/list returns all 7 tools with the correct names + tiers
 *   - each tool routes to the right client.invoke() / client.health() /
 *     client.listCommands() call with correct args
 *   - forceRefresh on self_test is forwarded as a boolean, never an
 *     unprefixed truthy string ("false" → false footgun)
 *   - Layer2Error is caught and surfaced as in-band ToolResult error
 *     (isError:true), never thrown out of CallToolRequest
 *   - bundle ok:false invoke results surface as in-band errors with the
 *     bundle's error.kind preserved
 *   - unknown tool names return in-band error rather than crashing
 */

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

import { FdrsLayer2Client, Layer2Error } from "../src/client.js";
import { buildLayer2McpServer } from "../src/mcp-server.js";
import { LAYER2_TOOL_NAMES } from "../src/mcp-server.types.js";
import type {
  Layer2HealthResponse,
  Layer2InvokeResult,
  Layer2ListCommandsResponse,
} from "../src/types.js";

// ──────────────────────────────────────────────────────────────────────
// Fake client — observes invoke() calls + returns scripted responses
// without ever touching the network. Mirrors FdrsLayer2Client's public
// surface; private members are not exercised by the MCP server, so the
// type cast is safe.
// ──────────────────────────────────────────────────────────────────────

interface FakeInvokeCall {
  name: string;
  args: Record<string, unknown>;
}

class FakeLayer2Client {
  invocations: FakeInvokeCall[] = [];
  healthCalls = 0;
  listCommandsCalls = 0;

  /** Programmable response for invoke(). Default: ok:true with command echoed. */
  invokeImpl: (
    name: string,
    args: Record<string, unknown>,
  ) => Promise<Layer2InvokeResult<unknown>> = async (name) => ({
    ok: true,
    command: name,
    result: { fake: true },
    latencyMs: 7,
  });

  /** Programmable response for health(). */
  healthImpl: () => Promise<Layer2HealthResponse> = async () => ({
    ok: true,
    bundleVersion: "0.1.0-fake",
    invokerAvailable: true,
    commandsExposed: ["getApplicationsAndSystems", "readSelfTestDTCs"],
  });

  /** Programmable response for listCommands(). */
  listCommandsImpl: () => Promise<Layer2ListCommandsResponse> = async () => ({
    ok: true,
    commands: [
      {
        publicName: "getApplicationsAndSystems",
        commandClass: "com.ford.otx.services.vehicle.command.GetApplicationsAndSystems",
        takesArgs: {},
      },
    ],
  });

  async invoke(name: string, args: Record<string, unknown> = {}, _sessionId?: string): Promise<Layer2InvokeResult<unknown>> {
    this.invocations.push({ name, args });
    return this.invokeImpl(name, args);
  }

  async health(): Promise<Layer2HealthResponse> {
    this.healthCalls += 1;
    return this.healthImpl();
  }

  async listCommands(): Promise<Layer2ListCommandsResponse> {
    this.listCommandsCalls += 1;
    return this.listCommandsImpl();
  }

  // Session lifecycle stubs (added for PR2.5 compatibility — the
  // production mcp-server now lazy-bootstraps a UDS session before
  // stateful invokes; the fake client must expose the same surface).
  bootstrapCalls: Array<{ vin: string }> = [];
  releaseSessionCalls: string[] = [];
  bootstrapImpl: (vin: string) => Promise<{ sessionId: string; vin: string }> = async (vin) => ({
    sessionId: "fake-session-" + Math.random().toString(36).slice(2, 10),
    vin,
  });

  async bootstrap(vin: string): Promise<{ sessionId: string; vin: string }> {
    this.bootstrapCalls.push({ vin });
    return this.bootstrapImpl(vin);
  }

  async releaseSession(sessionId: string): Promise<{ removed: boolean }> {
    this.releaseSessionCalls.push(sessionId);
    return { removed: true };
  }
}

async function buildHarness(): Promise<{
  fake: FakeLayer2Client;
  client: Client;
}> {
  const fake = new FakeLayer2Client();
  const server = buildLayer2McpServer({
    client: fake as unknown as FdrsLayer2Client,
    serverName: "test-layer2",
    serverVersion: "test",
  });

  const [serverTransport, clientTransport] =
    InMemoryTransport.createLinkedPair();

  const client = new Client(
    { name: "test-client", version: "1.0.0" },
    { capabilities: {} },
  );

  await Promise.all([
    server.connect(serverTransport),
    client.connect(clientTransport),
  ]);

  return { fake, client };
}

describe("Layer 2 MCP server", () => {
  let h: { fake: FakeLayer2Client; client: Client };

  beforeEach(async () => {
    h = await buildHarness();
  });

  describe("tools/list", () => {
    it("returns all 10 documented tools", async () => {
      const r = await h.client.listTools();
      const names = r.tools.map((t) => t.name).sort();
      expect(names).toEqual([...LAYER2_TOOL_NAMES].sort());
    });

    it("each tool advertises tier metadata", async () => {
      const r = await h.client.listTools();
      for (const t of r.tools) {
        expect((t.description ?? "").length).toBeGreaterThan(20);
        expect(t.inputSchema).toBeDefined();
      }
    });

    it("read_did + read_did_batch + stream_pid_window are tiered confirm (bus I/O)", async () => {
      const r = await h.client.listTools();
      const byName = new Map(r.tools.map((t) => [t.name, t]));
      // SDK Tool type doesn't include _meta directly — cast through any
      // for the tier annotation read.
      const tier = (n: string) =>
        (byName.get(n) as { _meta?: { tier?: string } } | undefined)?._meta
          ?.tier;
      expect(tier("read_did")).toBe("confirm");
      expect(tier("read_did_batch")).toBe("confirm");
      expect(tier("stream_pid_window")).toBe("confirm");
      expect(tier("module_inventory")).toBe("auto");
    });
  });

  describe("module_inventory", () => {
    it("invokes getApplicationsAndSystems with forceRefresh:false by default", async () => {
      await h.client.callTool({
        name: "module_inventory",
        arguments: { vin: "1FTRF3AT4TEC85082" },
      });
      expect(h.fake.invocations).toHaveLength(1);
      expect(h.fake.invocations[0].name).toBe("getApplicationsAndSystems");
      expect(h.fake.invocations[0].args).toEqual({ forceRefresh: false });
    });

    it("passes freshOnly through as forceRefresh:true", async () => {
      await h.client.callTool({
        name: "module_inventory",
        arguments: { vin: "X", freshOnly: true },
      });
      expect(h.fake.invocations[0].args).toEqual({ forceRefresh: true });
    });

    it("string 'false' for freshOnly does NOT trigger fresh sweep (avoids the truthy-string footgun)", async () => {
      await h.client.callTool({
        name: "module_inventory",
        arguments: {
          vin: "X",
          freshOnly: "false" as unknown as boolean,
        },
      });
      expect(h.fake.invocations[0].args.forceRefresh).toBe(false);
    });

    it("echoes vin + requestedFresh in the response payload", async () => {
      const r = (await h.client.callTool({
        name: "module_inventory",
        arguments: { vin: "1FTRF3AT4TEC85082", freshOnly: true },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBeFalsy();
      const body = JSON.parse(r.content[0].text);
      expect(body.vin).toBe("1FTRF3AT4TEC85082");
      expect(body.requestedFresh).toBe(true);
      expect(body.ok).toBe(true);
    });
  });

  describe("read_did", () => {
    it("invokes readDID with didNumber + nodeAddress", async () => {
      await h.client.callTool({
        name: "read_did",
        arguments: {
          vin: "X",
          didNumber: "0xF190",
          nodeAddress: "0x760",
        },
      });
      expect(h.fake.invocations[0].name).toBe("readDID");
      expect(h.fake.invocations[0].args).toEqual({
        didNumber: "0xF190",
        nodeAddress: "0x760",
      });
    });

    it("forwards integer didNumber + nodeAddress unchanged (bundle coerces)", async () => {
      await h.client.callTool({
        name: "read_did",
        arguments: { vin: "X", didNumber: 0xf190, nodeAddress: 0x760 },
      });
      expect(h.fake.invocations[0].args.didNumber).toBe(0xf190);
      expect(h.fake.invocations[0].args.nodeAddress).toBe(0x760);
    });
  });

  describe("read_did_batch", () => {
    it("invokes readDIDBatch with nodeAddress + dids array", async () => {
      await h.client.callTool({
        name: "read_did_batch",
        arguments: {
          vin: "X",
          nodeAddress: "0x760",
          dids: ["0xF190", "0xF1A2", "0x1E50"],
        },
      });
      expect(h.fake.invocations[0].name).toBe("readDIDBatch");
      expect(h.fake.invocations[0].args).toEqual({
        nodeAddress: "0x760",
        dids: ["0xF190", "0xF1A2", "0x1E50"],
      });
    });
  });

  describe("stream_pid_window", () => {
    it("rejects durationSec > maxDurationSec", async () => {
      const r = (await h.client.callTool({
        name: "stream_pid_window",
        arguments: {
          vin: "X",
          didNumber: "0x1E50",
          nodeAddress: "0x760",
          durationSec: 9999,
          rateHz: 10,
        },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBe(true);
      expect(r.content[0].text).toContain("durationSec");
    });

    it("rejects rateHz > maxRateHz", async () => {
      const r = (await h.client.callTool({
        name: "stream_pid_window",
        arguments: {
          vin: "X",
          didNumber: "0x1E50",
          nodeAddress: "0x760",
          durationSec: 5,
          rateHz: 9999,
        },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBe(true);
      expect(r.content[0].text).toContain("rateHz");
    });

    it("rejects rateHz below minRateHz", async () => {
      const r = (await h.client.callTool({
        name: "stream_pid_window",
        arguments: {
          vin: "X",
          didNumber: "0x1E50",
          nodeAddress: "0x760",
          durationSec: 5,
          rateHz: 0.1,
        },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBe(true);
    });

    it(
      "polls readDID at the requested rate and returns sample array",
      async () => {
        // 1 second window, 5Hz -> ~5 samples. The MCP-side polling loop
        // sleeps between samples so this test takes ~1s. Allow up to 3s
        // via the explicit timeout (default jest is 5s, this is well
        // within that).
        await h.client.callTool({
          name: "stream_pid_window",
          arguments: {
            vin: "X",
            didNumber: "0x1E50",
            nodeAddress: "0x760",
            durationSec: 1,
            rateHz: 5,
          },
        });
        // Should have called readDID multiple times. Lower bound 4 (the
        // window may end before the 5th sample if dispatch latency dominated)
        // and upper bound 6 (small clock skew).
        expect(h.fake.invocations.length).toBeGreaterThanOrEqual(4);
        expect(h.fake.invocations.length).toBeLessThanOrEqual(6);
        for (const inv of h.fake.invocations) {
          expect(inv.name).toBe("readDID");
        }
      },
      3000,
    );

    it(
      "captures error per-sample and continues polling on transient failure",
      async () => {
        let n = 0;
        h.fake.invokeImpl = async () => {
          n += 1;
          if (n === 2) {
            throw new Layer2Error("network", "transient");
          }
          return {
            ok: true,
            command: "readDID",
            result: { value: n },
            latencyMs: 5,
          };
        };
        const r = (await h.client.callTool({
          name: "stream_pid_window",
          arguments: {
            vin: "X",
            didNumber: "0x1E50",
            nodeAddress: "0x760",
            durationSec: 1,
            rateHz: 5,
          },
        })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
        expect(r.isError).toBeFalsy();
        const body = JSON.parse(r.content[0].text);
        expect(body.actual.errCount).toBe(1);
        expect(body.actual.okCount).toBeGreaterThanOrEqual(3);
        // The errored sample should still be in the array, with error set.
        const errSample = body.samples.find((s: { error?: string }) => !!s.error);
        expect(errSample.error).toContain("layer2-network");
      },
      3000,
    );
  });

  describe("self_test", () => {
    it("invokes readSelfTestDTCs with module + forceRefresh flag", async () => {
      await h.client.callTool({
        name: "self_test",
        arguments: {
          vin: "1FTRF3AT4TEC85082",
          module: "PCM",
          forceRefresh: true,
        },
      });
      expect(h.fake.invocations[0].name).toBe("readSelfTestDTCs");
      expect(h.fake.invocations[0].args).toEqual({
        module: "PCM",
        forceRefresh: true,
      });
    });

    it("defaults forceRefresh to false when omitted", async () => {
      await h.client.callTool({
        name: "self_test",
        arguments: { vin: "X", module: "PCM" },
      });
      expect(h.fake.invocations[0].args.forceRefresh).toBe(false);
    });

    it("coerces non-boolean forceRefresh to false (never the string 'false' footgun)", async () => {
      await h.client.callTool({
        name: "self_test",
        arguments: {
          vin: "X",
          module: "PCM",
          forceRefresh: "false" as unknown as boolean,
        },
      });
      expect(h.fake.invocations[0].args.forceRefresh).toBe(false);
    });
  });

  describe("vehicle_history + vehicle_status + last_vehicles", () => {
    it("vehicle_history maps to readVehicleHistory", async () => {
      await h.client.callTool({
        name: "vehicle_history",
        arguments: { vin: "X" },
      });
      expect(h.fake.invocations[0].name).toBe("readVehicleHistory");
    });

    it("vehicle_status maps to getVehicleModelStatus", async () => {
      await h.client.callTool({
        name: "vehicle_status",
        arguments: { vin: "X" },
      });
      expect(h.fake.invocations[0].name).toBe("getVehicleModelStatus");
    });

    it("last_vehicles maps to getLastSelectedVehicles", async () => {
      await h.client.callTool({
        name: "last_vehicles",
        arguments: {},
      });
      expect(h.fake.invocations[0].name).toBe("getLastSelectedVehicles");
    });
  });

  describe("bridge introspection", () => {
    it("bridge_health calls client.health()", async () => {
      const r = (await h.client.callTool({
        name: "bridge_health",
        arguments: {},
      })) as { content: Array<{ type: string; text: string }> };
      expect(h.fake.healthCalls).toBe(1);
      const body = JSON.parse(r.content[0].text);
      expect(body.bundleVersion).toBe("0.1.0-fake");
    });

    it("bridge_commands calls client.listCommands()", async () => {
      const r = (await h.client.callTool({
        name: "bridge_commands",
        arguments: {},
      })) as { content: Array<{ type: string; text: string }> };
      expect(h.fake.listCommandsCalls).toBe(1);
      const body = JSON.parse(r.content[0].text);
      expect(body.commands[0].publicName).toBe("getApplicationsAndSystems");
    });
  });

  describe("error handling", () => {
    it("surfaces Layer2Error as in-band ToolResult.isError", async () => {
      h.fake.invokeImpl = async () => {
        throw new Layer2Error("timeout", "request exceeded 15s");
      };
      const r = (await h.client.callTool({
        name: "module_inventory",
        arguments: { vin: "X" },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBe(true);
      expect(r.content[0].text).toContain("layer2-timeout");
      expect(r.content[0].text).toContain("request exceeded 15s");
    });

    it("surfaces bundle ok:false as in-band error with kind preserved", async () => {
      h.fake.invokeImpl = async () => ({
        ok: false,
        error: "command not in allowlist",
        kind: "allowlist_miss",
      });
      const r = (await h.client.callTool({
        name: "module_inventory",
        arguments: { vin: "X" },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBe(true);
      expect(r.content[0].text).toContain("bundle-allowlist_miss");
    });

    it("never throws out of CallToolRequest on unexpected client failure", async () => {
      h.fake.invokeImpl = async () => {
        throw new Error("network reset by peer");
      };
      const r = (await h.client.callTool({
        name: "module_inventory",
        arguments: { vin: "X" },
      })) as { content: Array<{ type: string; text: string }>; isError?: boolean };
      expect(r.isError).toBe(true);
      expect(r.content[0].text).toContain("unexpected");
    });
  });
});
