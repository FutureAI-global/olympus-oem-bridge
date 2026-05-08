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
  ): Promise<Layer2InvokeResult<T>> {
    return this.postJson<Layer2InvokeResult<T>>(
      `/commands/${encodeURIComponent(name)}`,
      { args },
    );
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
