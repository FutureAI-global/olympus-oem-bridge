import type {
  Layer2ClientConfig,
  Layer2CommandArgs,
  Layer2CommandName,
  Layer2HealthResponse,
  Layer2InvokeResult,
  Layer2ListCommandsResponse,
} from "./types.js";

export class Layer2Error extends Error {
  readonly kind: "timeout" | "network" | "protocol";
  constructor(kind: "timeout" | "network" | "protocol", message: string) {
    super(message);
    this.name = "Layer2Error";
    this.kind = kind;
  }
}

export class FdrsLayer2Client {
  private readonly baseUrl: string;
  private readonly timeoutMs: number;

  constructor(cfg: Layer2ClientConfig = {}) {
    const raw = cfg.baseUrl ?? "http://127.0.0.1:18082";
    this.baseUrl = raw.endsWith("/") ? raw.slice(0, -1) : raw;
    this.timeoutMs = cfg.requestTimeoutMs ?? 15_000;
  }

  async health(): Promise<Layer2HealthResponse> {
    return this.getJson<Layer2HealthResponse>("/health");
  }

  async listCommands(): Promise<Layer2ListCommandsResponse> {
    return this.getJson<Layer2ListCommandsResponse>("/commands");
  }

  async invoke<T = unknown>(
    name: Layer2CommandName,
    args: Layer2CommandArgs = {},
    sessionId?: string,
  ): Promise<Layer2InvokeResult<T>> {
    const body: { args: Layer2CommandArgs; sessionId?: string } = { args };
    if (sessionId !== undefined && sessionId.length > 0) {
      body.sessionId = sessionId;
    }
    return this.postJson<Layer2InvokeResult<T>>(
      `/commands/${encodeURIComponent(name)}`,
      body,
    );
  }

  /**
   * Bootstrap a UDS session against a VIN. Required before any command
   * that needs vehicle bus context (readDID, readDIDBatch, readSelfTestDTCs,
   * streamPID, etc). Returns a sessionId that callers pass back on
   * subsequent invoke()s to participate in the live UDS session.
   *
   * Without bootstrap, "stateless" commands like getApplicationsAndSystems
   * still work (they're metadata about installed FDRS tools, not vehicle
   * reads). Stateful commands return null/empty until a session exists.
   *
   * Discovered the hard way 2026-05-09: Session N's bench showed readDID
   * returning {ok:true, result:null} on both VINs because no session was
   * bootstrapped. Same root cause for stream_pid_window samples being
   * non-null-keyed but null-valued.
   */
  async bootstrap(vin: string): Promise<{ sessionId: string; vin: string }> {
    if (typeof vin !== "string" || vin.length === 0) {
      throw new Layer2Error("protocol", "bootstrap requires a non-empty vin");
    }
    const r = await this.postJson<{
      sessionId?: string;
      vin?: string;
      ok?: boolean;
      error?: string;
    }>("/session/bootstrap", { vin });
    if (!r.sessionId || typeof r.sessionId !== "string") {
      throw new Layer2Error(
        "protocol",
        `bootstrap response missing sessionId: ${JSON.stringify(r).slice(0, 200)}`,
      );
    }
    return { sessionId: r.sessionId, vin: r.vin ?? vin };
  }

  /**
   * Release a previously-bootstrapped session. Idempotent: if the session
   * already expired or never existed, the bridge returns a 200 with
   * removed:false. We never throw on that.
   */
  async releaseSession(sessionId: string): Promise<{ removed: boolean }> {
    if (!sessionId) return { removed: false };
    const r = await this.request<{ removed?: boolean; ok?: boolean }>(
      "DELETE",
      `/session/${encodeURIComponent(sessionId)}`,
    );
    return { removed: !!r.removed };
  }

  private async getJson<T>(path: string): Promise<T> {
    return this.request<T>("GET", path);
  }

  private async postJson<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>("POST", path, body);
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const res = await fetch(`${this.baseUrl}${path}`, {
        method,
        signal: controller.signal,
        headers: body ? { "content-type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined,
      });
      const text = await res.text();
      try {
        return JSON.parse(text) as T;
      } catch {
        throw new Layer2Error(
          "protocol",
          `non-JSON response (status ${res.status}): ${text.slice(0, 200)}`,
        );
      }
    } catch (err) {
      if ((err as { name?: string } | null)?.name === "AbortError") {
        throw new Layer2Error("timeout", `${method} ${path} timed out after ${this.timeoutMs}ms`);
      }
      if (err instanceof Layer2Error) throw err;
      throw new Layer2Error("network", `${method} ${path} failed: ${(err as Error).message}`);
    } finally {
      clearTimeout(timer);
    }
  }
}
