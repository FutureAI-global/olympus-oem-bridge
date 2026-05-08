"""
Reference bridge skeleton — any OEM, any runtime that can host Python.

Implements the bridge HTTP protocol (see ../../docs/BRIDGE_PROTOCOL.md):
  GET  /health             — liveness + introspection
  GET  /commands           — list allowlisted commands
  POST /commands/<name>    — invoke a single command with JSON args

Localhost-only by design. Allowlist-enforced. Stdlib HTTP server only —
no external deps so this can run inside an OEM tool's Python sandbox
without classpath/dependency conflicts.

Replace the four MARK: blocks with your OEM-specific code:
  1. discover_command_bus()  — find the OEM tool's command-dispatch service
  2. CommandAdapter classes  — one per command you want to expose
  3. ALLOWLIST               — register your adapters
  4. BUNDLE_VERSION          — semver of your bridge

Then run alongside the OEM tool. Verify with:
  curl http://127.0.0.1:18082/health
  curl http://127.0.0.1:18082/commands
  curl -X POST http://127.0.0.1:18082/commands/<your-cmd> \\
       -H 'content-type: application/json' \\
       -d '{"args":{}}'
"""

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

# ────────────────────────────────────────────────────────────────────
# Bridge identity
# ────────────────────────────────────────────────────────────────────

# MARK: 4. set your bridge version. Bump on every release.
BUNDLE_VERSION = "0.0.1"

LISTEN_HOST = "127.0.0.1"  # DO NOT change to 0.0.0.0 — see SECURITY.md
LISTEN_PORT = 18082


# ────────────────────────────────────────────────────────────────────
# OEM tool integration — fill in for your specific tool
# ────────────────────────────────────────────────────────────────────


def discover_command_bus() -> Any:
    """
    MARK: 1. Discover the OEM tool's internal command-dispatch service.

    For OSGi-based tools (Ford FDRS, BMW ISTA-D, some Mercedes XENTRY):
        from osgi import BundleContext
        return BundleContext.get_service_reference("com.oem.CommandInvoker")

    For .NET-based tools (some GM, Stellantis):
        import clr
        clr.AddReference("OemTool.Diagnostic")
        from OemTool.Diagnostic import ServiceLocator
        return ServiceLocator.GetService(ICommandBus)

    For Electron-based tools (newer Toyota, Honda):
        from electron_ipc import get_ipc_main
        return get_ipc_main().request("diagnostic.bus")

    Returns whatever object the OEM tool uses for command dispatch.
    The CommandAdapter classes below will call methods on this object.
    """
    raise NotImplementedError(
        "Implement discover_command_bus() for your OEM tool"
    )


# ────────────────────────────────────────────────────────────────────
# Command adapters
# ────────────────────────────────────────────────────────────────────


class CommandAdapter:
    """Subclass per command. publicName is what external callers use."""

    publicName: str = ""
    commandClass: str = ""

    def takesArgs(self) -> dict[str, str]:
        """Map of arg name → human description. Free-form text."""
        return {}

    def invoke(self, bus: Any, args: dict[str, Any]) -> Any:
        """Dispatch the command and return the result (will be JSON-serialized)."""
        raise NotImplementedError


# MARK: 2. one CommandAdapter subclass per OEM command you want to expose.
# Start with ONE read-only command. Add more in follow-up PRs.

class ListModulesAdapter(CommandAdapter):
    """Example: list connected vehicle modules. Read-only."""

    publicName = "listModules"
    commandClass = "<oem-tool's class name for this command>"

    def takesArgs(self) -> dict[str, str]:
        return {}

    def invoke(self, bus: Any, args: dict[str, Any]) -> Any:
        # Replace with your OEM's actual API:
        #   return bus.invoke("ListConnectedModules")
        # Or:
        #   return bus.execute_command("get_modules", {})
        raise NotImplementedError("wire to OEM tool")


# MARK: 3. allowlist — only these commands are reachable via /commands/<name>
ALLOWLIST: list[CommandAdapter] = [
    ListModulesAdapter(),
]


# ────────────────────────────────────────────────────────────────────
# HTTP server — generic, do not modify unless you have a good reason
# ────────────────────────────────────────────────────────────────────


def find_adapter(name: str) -> CommandAdapter | None:
    for a in ALLOWLIST:
        if a.publicName == name:
            return a
    return None


def health_response(bus: Any) -> dict[str, Any]:
    return {
        "ok": True,
        "bundleVersion": BUNDLE_VERSION,
        "invokerAvailable": bus is not None,
        "commandsExposed": [a.publicName for a in ALLOWLIST],
    }


def commands_response() -> dict[str, Any]:
    return {
        "ok": True,
        "commands": [
            {
                "publicName": a.publicName,
                "commandClass": a.commandClass,
                "takesArgs": a.takesArgs(),
            }
            for a in ALLOWLIST
        ],
    }


def invoke_command(
    bus: Any, name: str, args: dict[str, Any]
) -> tuple[int, dict[str, Any]]:
    adapter = find_adapter(name)
    start = time.monotonic()
    if adapter is None:
        # Distinguish "not in allowlist" from "doesn't exist" by checking
        # all known commands first; for now both surface as not_found.
        return 200, {
            "ok": False,
            "error": f"command {name!r} not in allowlist",
            "kind": "allowlist_miss",
            "command": name,
            "latencyMs": int((time.monotonic() - start) * 1000),
        }
    if bus is None:
        return 200, {
            "ok": False,
            "error": "OEM command bus not available",
            "kind": "invoker_unavailable",
            "command": name,
            "latencyMs": int((time.monotonic() - start) * 1000),
        }
    try:
        result = adapter.invoke(bus, args)
    except Exception as e:  # pylint: disable=broad-except
        return 200, {
            "ok": False,
            "error": str(e)[:300],
            "kind": "invocation_error",
            "command": name,
            "latencyMs": int((time.monotonic() - start) * 1000),
        }
    try:
        # Verify result is JSON-serializable up-front so we can return a
        # specific error kind instead of a 500.
        body = {
            "ok": True,
            "command": name,
            "result": result,
            "latencyMs": int((time.monotonic() - start) * 1000),
        }
        json.dumps(body)
        return 200, body
    except (TypeError, ValueError) as e:
        return 200, {
            "ok": False,
            "error": f"result not JSON-serializable: {e!s:.200}",
            "kind": "serialize_error",
            "command": name,
        }


class BridgeHandler(BaseHTTPRequestHandler):
    """HTTP request handler. Stateless — bus is set on the server instance."""

    # Suppress the noisy "GET /health 200" line per request.
    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def _write(self, status: int, body: dict[str, Any]) -> None:
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        bus = getattr(self.server, "bus", None)
        if self.path == "/health":
            self._write(200, health_response(bus))
        elif self.path == "/commands":
            self._write(200, commands_response())
        else:
            self._write(404, {"ok": False, "error": "not found", "kind": "not_found"})

    def do_POST(self) -> None:
        if not self.path.startswith("/commands/"):
            self._write(404, {"ok": False, "error": "not found", "kind": "not_found"})
            return
        name = self.path[len("/commands/") :]
        try:
            length = int(self.headers.get("content-length", "0"))
            raw = self.rfile.read(length) if length > 0 else b"{}"
            body = json.loads(raw.decode("utf-8") or "{}")
        except (ValueError, json.JSONDecodeError) as e:
            self._write(
                200,
                {
                    "ok": False,
                    "error": f"bad request body: {e!s:.200}",
                    "kind": "bad_request",
                },
            )
            return
        args = body.get("args") if isinstance(body, dict) else None
        if not isinstance(args, dict):
            self._write(
                200,
                {"ok": False, "error": "args must be an object", "kind": "bad_args"},
            )
            return
        bus = getattr(self.server, "bus", None)
        status, payload = invoke_command(bus, name, args)
        self._write(status, payload)


def main() -> None:
    bus = None
    try:
        bus = discover_command_bus()
    except NotImplementedError:
        # Allow the bridge to start in a degraded "invokerAvailable: false"
        # state so /health still works during development. Production
        # bridges should crash here — fix discover_command_bus first.
        print("[bridge] WARNING: command bus not implemented yet")

    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), BridgeHandler)
    server.bus = bus  # type: ignore[attr-defined]
    print(f"[bridge] listening on http://{LISTEN_HOST}:{LISTEN_PORT}")
    print(f"[bridge] version {BUNDLE_VERSION}, {len(ALLOWLIST)} commands exposed")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()


if __name__ == "__main__":
    main()
