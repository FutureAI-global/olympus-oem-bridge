export type Layer2CommandName =
  | "getLastSelectedVehicles"
  | "getApplicationsAndSystems"
  | "getVehicleModelStatus"
  | "getInstalledMeasurementDevices"
  | "getMeasurementDevice"
  | (string & {});

export type Layer2CommandArgs = Record<string, unknown>;

export interface Layer2HealthResponse {
  ok: true;
  bundleVersion: string;
  invokerAvailable: boolean;
  commandsExposed: string[];
}

export interface Layer2CommandEntry {
  publicName: string;
  commandClass: string;
  takesArgs: Record<string, string>;
}

export interface Layer2ListCommandsResponse {
  ok: true;
  commands: Layer2CommandEntry[];
}

export type Layer2ErrorKind =
  | "not_found"
  | "allowlist_miss"
  | "invoker_unavailable"
  | "bad_request"
  | "bad_args"
  | "invocation_error"
  | "serialize_error"
  | "internal_error"
  | "network_error"
  | "timeout";

export type Layer2InvokeResult<T = unknown> =
  | {
      ok: true;
      command: string;
      result: T;
      latencyMs: number;
    }
  | {
      ok: false;
      error: string;
      kind: Layer2ErrorKind;
      command?: string;
      latencyMs?: number;
    };

export interface Layer2ClientConfig {
  baseUrl?: string;
  requestTimeoutMs?: number;
}
