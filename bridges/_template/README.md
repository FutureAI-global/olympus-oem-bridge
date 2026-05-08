# Bridge template

Skeleton for a new OEM diagnostic bridge. Copy this directory to `bridges/<your-oem-name>/` and fill in the blanks.

## Files

| File | Purpose |
|---|---|
| [`bridge.py`](./bridge.py) | Reference Python skeleton — full HTTP protocol, allowlist, error handling. Replace the four `MARK:` blocks with your OEM-specific code. Stdlib-only (no external deps). |
| `README.md` (this) | Walkthrough |

The Python skeleton is the easiest starting point even if your OEM tool doesn't run Python — you can use it as a sidecar process that talks to the OEM tool over IPC, named pipes, or a local socket. For OEM tools that require in-process loading (OSGi bundles, .NET assemblies), study [`bridges/ford-fdrs/`](../ford-fdrs/) for the OSGi reference impl and adapt the same shape to your runtime.

## What you're building

A small piece of code that:
1. Loads into the OEM tool's runtime (OSGi bundle, .NET DLL, Electron module, etc.)
2. Discovers the OEM tool's existing internal command bus
3. Exposes a small HTTP server on `127.0.0.1:18082` that speaks the [bridge protocol](../../docs/BRIDGE_PROTOCOL.md)
4. Allowlists which commands external callers can invoke

See [`docs/ADDING_AN_OEM.md`](../../docs/ADDING_AN_OEM.md) for the full walkthrough.

## Reference impl

Look at [`bridges/ford-fdrs/`](../ford-fdrs/) for a complete, production-validated bridge. Key files to study:

| File | Purpose |
|---|---|
| `src/main/java/com/futureai/fdrs/layer2/Activator.java` | OSGi entry-point — registers the bridge service when the tool loads it |
| `src/main/java/com/futureai/fdrs/layer2/Bridge.java` | Top-level lifecycle |
| `src/main/java/com/futureai/fdrs/layer2/BundleHttpServer.java` | Minimal HTTP server (uses Java SE `com.sun.net.httpserver`, no external deps) |
| `src/main/java/com/futureai/fdrs/layer2/Router.java` | URL → adapter dispatch |
| `src/main/java/com/futureai/fdrs/layer2/CommandAdapter.java` | Interface every command implements |
| `src/main/java/com/futureai/fdrs/layer2/CommandAdapterRegistry.java` | The allowlist |
| `src/main/java/com/futureai/fdrs/layer2/adapter/ReadDIDAdapter.java` | Concrete adapter — wraps Ford's `ReadDID` command class |

## Minimum viable bridge

For PR1 to your bridge, you need:

1. **A loader.** Whatever mechanism the OEM runtime uses (OSGi `Bundle-Activator`, .NET assembly entry, Electron preload, etc.)
2. **An HTTP server bound to `127.0.0.1:18082`.** Use the runtime's stdlib HTTP — don't pull in Jetty/Express/Kestrel that might conflict with the OEM tool's existing classpath.
3. **Three endpoints:** `GET /health`, `GET /commands`, `POST /commands/<name>`. See [`docs/BRIDGE_PROTOCOL.md`](../../docs/BRIDGE_PROTOCOL.md) for exact response shapes.
4. **At least ONE allowlisted read-only command** — typically `listModules` or `getVehicleStatus`. Ship one command in PR1; add more in follow-up PRs.

## What you don't need

- A build system more complex than what the OEM runtime needs. Ford-FDRS uses a simple bash script that calls `javac` against the JDK Ford ships. Don't pull in Maven/Gradle/SBT unless your OEM toolchain forces it.
- An MCP wrapper — that's generic, in [`mcp/`](../../mcp/), and works against any compliant bridge.
- A test framework — start with manual smoke testing via curl. Add automated tests in PR2 if the bridge is solid.

## Filing a PR

PR title format: `feat(<oem-name>): bridge bundle + N read-only commands`

PR description checklist:
- [ ] OEM tool name + version range tested against
- [ ] Loading mechanism (OSGi / .NET DLL / Electron module / etc.)
- [ ] How the bridge discovers the OEM tool's command bus
- [ ] Initial allowlist (commands shipped in PR1)
- [ ] One real-vehicle smoke test result (e.g. `curl http://127.0.0.1:18082/commands/listModules` output, redacted as needed)
- [ ] Confirmation that no DRM/licensing bypass is involved
- [ ] Confirmation that no safety-critical writes are in the allowlist
