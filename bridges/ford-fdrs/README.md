# futureai-fdrs-layer2-bridge

OSGi bundle that runs **inside FDRS's Felix container** and exposes Ford's internal
diagnostic command surface to Olympus over the network.

This is the Option-A implementation of FDRS Layer 2 — see
`../backend/src/services/fdrs/layer2/recon/README.md` for why Layer 2 needed
an OSGi bundle instead of a REST probe.

## Status

| Phase | Done | Description |
|---|---|---|
| 1 | 🟡 in progress | Build + deploy a passive probe bundle; verify it loads, logs, and can reach `OSGICommandInvoker` |
| 2 | ⬜ | Embed Jetty in the bundle on port 18082; expose `GET /health` and `GET /commands` |
| 3 | ⬜ | Expose `POST /commands/<name>` with an allowlist + arg marshaling; Olympus client in `backend/src/services/fdrs/layer2/osgi-client.ts` |
| 4 | ⬜ | Safety case sign-off; allowlist >= 3 read-only commands; MCP handler routes through |

Phase 1 deliverable is deliberately minimal: the goal is to prove the bundle
format is accepted, the Activator runs, and we can look up the `OSGICommandInvoker`
service reference — not yet to invoke commands.

## Layout

```
fdrs-layer2-bridge/
├── src/main/java/com/futureai/fdrs/layer2/Activator.java
├── src/main/resources/META-INF/MANIFEST.MF
├── scripts/build.sh    ← uses FDRS's shipped JDK 17
├── scripts/deploy.sh   ← manual-only, copies into FDRS bundle/ dir
├── lib/                ← reserved for compile-only JARs copied out of FDRS install
└── build/              ← output (gitignored)
```

## Build

Requires FDRS installed at the default Windows path (the build script reads its
JDK and Felix main JAR from there). No host-wide Java toolchain needed.

```bash
cd fdrs-layer2-bridge
./scripts/build.sh
# -> build/jar/futureai-fdrs-layer2-bridge-0.1.0.jar
```

The output is a valid OSGi R6 bundle with:

- `Bundle-SymbolicName: com.futureai.fdrs.layer2`
- `Bundle-Activator: com.futureai.fdrs.layer2.Activator`
- `Import-Package: org.osgi.framework;version="[1.6,2.0)"`

Verify manually:

```bash
unzip -p build/jar/futureai-fdrs-layer2-bridge-0.1.0.jar META-INF/MANIFEST.MF
```

## Deploy (manual, destructive)

**DO NOT run during a live vehicle diagnostic session.** Deploying requires
restarting FDRS which drops any in-progress VCM3 connection.

```bash
# After building:
./scripts/deploy.sh               # copies JAR into FDRS bundle/ dir
# ... then manually close & relaunch FDRS ...
tail -f ~/.olympus/fdrs/fdrs-runtime.log | grep -i futureai
```

Expected first line once FDRS is ready:

```
INFO: futureai-fdrs-layer2-bridge v0.1.0 starting; bundleId=<N>
```

To roll back:

```bash
rm "/c/Program Files (x86)/Ford Motor Company/FDRS/bundle/futureai-fdrs-layer2-bridge-0.1.0.jar"
# ... then restart FDRS ...
```

## Why this approach

- **OSGICommandInvoker** (Ford bundle `com.ford.otx.command.invoker.osgi-40.24.26`)
  dispatches ~96 named commands covering vehicle select, datalogger, self-test,
  OTX compile/run, user auth. It's the exact layer FDRS's CEF JS-to-Java bridge
  already uses — we're not inventing a new protocol, we're exposing one that
  already exists.
- Running inside Felix gives us `Import-Package` access to the invoker without
  reflection or JVM attach. The bundle is a normal OSGi dependency consumer.
- The 352 FDRS bundles in `bundle/` do not appear signed at the Felix
  framework level (`config.properties` has no `felix.fileinstall.bundle.signed`
  setting, no signing provider is wired in), so unsigned third-party bundles
  install-and-start via `felix.auto.deploy.action=install,start`.

## Safety case (forward-looking)

Before Phase 3 (`POST /commands/<name>`) ships, this bundle must carry an
embedded allowlist of command names it will invoke — refusing everything else
with `HTTP 403 allowlist_miss`. The allowlist starts empty; each addition is a
PR that cites a safety-case section.

See `../backend/docs/safety-case-fdrs-layer2-v1.0.md` (not yet written — Phase 3 deliverable).
