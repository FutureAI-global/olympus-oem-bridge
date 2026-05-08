# Adding a new OEM bridge

This guide walks through writing a bridge for an OEM diagnostic tool not yet covered by this repo.

## Step 1: Pick the OEM tool's runtime

Identify what kind of process the OEM tool runs in:

- **OSGi** (Felix or Equinox) — Ford FDRS, some BMW tools. The bridge is a JAR with a `Bundle-Activator`.
- **.NET** (CLR) — many GM, Stellantis, Mercedes tools. The bridge is a DLL loaded via `AppDomain.Load()` or a profiler.
- **Electron / Chromium** — newer Toyota, Honda, Tesla tools. The bridge is a JS module loaded into the renderer.
- **Native** (C/C++) — older Volvo, Saab tools. The bridge is a DLL injected via `LoadLibrary` or `LD_PRELOAD`.
- **Java Swing/SWT (no OSGi)** — BMW ISTA-D, some Mercedes XENTRY versions. The bridge is a JAR added to the classpath.

The runtime determines the language + loading mechanism for your bridge code.

## Step 2: Find the OEM tool's internal command bus

Every diagnostic tool has SOME way the UI calls into the diagnostic engine. The bridge taps that.

Look for:
- An OSGi service registry → `BundleContext.getServiceReference("...")`
- A WCF/.NET service locator → `ServiceLocator.GetService<ICommandBus>()`
- An Electron IPC channel → `ipcRenderer.send(...)` or a global `window.diagnostic.invoke(...)`
- A Java reflection-accessible singleton → `MainController.getInstance().dispatch(...)`
- A native function table → exported via DLL or accessed via vtable scrape

How to find it without source:
1. **Load the OEM tool, attach a debugger, breakpoint a known UI action** (e.g. clicking "Read DTCs"). The call stack from the UI down to the bus is the path you need to expose.
2. **Decompile the OEM tool's main JAR/DLL** with JD-GUI / dnSpy / Ghidra. Look for class names like `CommandBus`, `ServiceLocator`, `RequestDispatcher`, `Invoker`.
3. **Trace `OutputDebugString` / log4j output.** Many tools log "dispatching command X" with the command name. That's the dispatcher.

## Step 3: Write a minimal bridge

Use `bridges/_template/` as a starting point. The template ships:
- A skeleton `Activator` (OSGi) or equivalent loading entry-point
- A minimal HTTP server on `127.0.0.1:18082` (use only the runtime's stdlib — no external HTTP libs that might conflict with the OEM tool's classpath)
- A `CommandAdapter` interface that wraps a single OEM command
- A `Router` that dispatches by URL → adapter → result

Your job: fill in the OEM-specific parts:
1. Discover the OEM tool's command bus (Step 2).
2. Implement one or more `CommandAdapter` subclasses that call into the bus.
3. Register them with the bridge's allowlist.

For the FIRST command, pick something cheap and read-only — typically "list connected modules" or "get vehicle status." Don't try to ship 20 adapters in PR1.

## Step 4: Verify the bridge loads

The OEM tool should start the bridge automatically (via the loading mechanism for its runtime). Verify:

```bash
# 1. Bundle/library is in the right place
ls "<OEM-tool-install>/bundle/" | grep <your-bridge>

# 2. OEM tool starts without errors
# (check OEM tool's log file)

# 3. Bridge is listening
curl http://127.0.0.1:18082/health

# 4. At least one command is exposed
curl http://127.0.0.1:18082/commands
```

If `/health` shows `invokerAvailable: false`, your bridge loaded but couldn't find the OEM tool's command bus. Re-check Step 2.

## Step 5: Wrap as an MCP server

Once the bridge is responding to HTTP, the MCP wrapping is mostly the same code regardless of OEM. Copy `mcp/bin/olympus-fdrs-layer2-mcp.ts` to `mcp/bin/<your-oem>-mcp.ts` and:
1. Update tool names to match your bridge's commands.
2. Update tool descriptions for the OEM-specific semantics.
3. Update arg schemas to match your bridge's `takesArgs`.

The shape of `mcp/src/mcp-server.ts` (the generic MCP server) doesn't change — it just gets a different config object.

## Step 6: Open a PR

Add your bridge under `bridges/<oem-name>/`. Add the MCP launcher under `mcp/bin/<oem-name>-mcp.ts`. Update the top-level README's status table. Open a PR.

Required for merge:
- [ ] Bridge builds against the OEM tool's runtime
- [ ] At least one read-only command is tested end-to-end against the real OEM tool with a real vehicle
- [ ] Allowlist contains only commands explicitly justified in the PR description
- [ ] No bypassing of OEM licensing or DRM
- [ ] No commands that interact with safety-critical systems (powertrain mutations, brakes, steering) without an explicit safety case

Reviewers look for the same things: localhost-only binding, minimal HTTP surface, allowlist enforcement, justified read-only commands.

## What you DON'T need to write

- The MCP transport (it's generic, in `mcp/src/`).
- An AI agent (this repo is just the bridge; AI agents are external consumers).
- A diagnostic playbook (that lives in the consuming product, not here).
- A UI (the bridge is headless; visualization is the consumer's responsibility).

## Example: hypothetical Porsche PIWIS bridge

Porsche's PIWIS 3 runs on Windows as a Java Swing application. It uses an internal `EquinoxCommandService` to dispatch named diagnostic commands. A bridge would:

1. Be packaged as an OSGi bundle (Equinox-flavored).
2. Drop into `<PIWIS-install>/plugins/`.
3. Discover `EquinoxCommandService` via the OSGi service registry.
4. Expose 3-5 read-only commands as a starting point: `listModules`, `readControlUnitVersion`, `readDTCs`, `readActualValues`, `readVehicleData`.
5. Open a PR titled `feat(porsche-piwis): bridge bundle + 5 read-only commands`.

After merge, the same MCP server pattern that today drives FDRS would drive PIWIS — same wire shape, different bundle URL.

## Help

Questions, stuck on Step 2, or want a sanity check before opening a PR? Open an issue on this repo with the OEM tool name + what you've found so far. Other contributors who've done bridges for similar runtimes can advise.
