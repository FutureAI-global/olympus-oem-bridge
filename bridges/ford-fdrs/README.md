# Ford FDRS bridge (`futureai-fdrs-layer2-bridge`)

OSGi bundle that loads inside Ford FDRS's Felix container and exposes Ford's internal `OSGICommandInvoker` surface to external HTTP clients on `127.0.0.1:18082`.

This is the production reference implementation for the [bridge protocol](../../docs/BRIDGE_PROTOCOL.md). It has been running in real diagnostic sessions on a real Ford F-150 since 2026-04-24. If you're writing a bridge for a different OEM, study this directory's structure and adapt.

## Status

Production-validated for read-only commands. Allowlist contains 18 commands as of 2026-05-08 covering:

- Module inventory + status (`getApplicationsAndSystems`, `getVehicleModelStatus`, `listModules`)
- DTC reads (`readSelfTestDTCs`)
- Live PIDs (`readDID`, `readDIDBatch`)
- Vehicle history (`readVehicleHistory`)
- Session metadata (`getCurrentVin`, `getLastSelectedVehicles`, `getEngineeringData`)
- Network test status (`checkNetworkTestRan`, `getLastNetworkTestRan`)
- Service-bulletin lookups (`getServiceBulletinsForDTCs`)
- VIN process + module mapping (`isCheckVinRequired`, `performVehicleIDProcess`, `getNodeToMdxMapping`, `isEvisDataExpired`)

All read-only. New commands require a PR with safety justification (see [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md)).

## Layout

```
bridges/ford-fdrs/
├── src/main/java/com/futureai/fdrs/layer2/
│   ├── Activator.java                — OSGi entry-point
│   ├── Bridge.java                   — top-level lifecycle
│   ├── BundleHttpServer.java         — minimal HTTP server (stdlib only)
│   ├── Router.java                   — URL → adapter dispatch
│   ├── CommandAdapter.java           — interface every command implements
│   ├── CommandAdapterRegistry.java   — the allowlist
│   ├── HttpIo.java                   — request/response helpers
│   ├── Json.java                     — bean walker (no Jackson, avoids classpath conflict)
│   ├── adapter/                      — one file per allowlisted command
│   ├── activetest/                   — active-test framework (read-only mode)
│   └── session/                      — bundle session lifecycle
├── src/main/resources/META-INF/MANIFEST.MF — OSGi manifest
├── scripts/build.sh                   — build the JAR (uses FDRS's shipped JDK 17)
└── scripts/deploy.sh                  — copy JAR into FDRS bundle/ dir (manual, restarts FDRS)
```

## Build

Requires Ford FDRS installed at the default Windows path (the build script reads its JDK and Felix main JAR from there). No host-wide Java toolchain needed.

```bash
cd bridges/ford-fdrs
./scripts/build.sh
# -> build/jar/futureai-fdrs-layer2-bridge-X.Y.Z.jar
```

The output is a valid OSGi R6 bundle with:

- `Bundle-SymbolicName: com.futureai.fdrs.layer2`
- `Bundle-Activator: com.futureai.fdrs.layer2.Activator`
- `Import-Package: org.osgi.framework;version="[1.6,2.0)"`

Verify the manifest:

```bash
unzip -p build/jar/futureai-fdrs-layer2-bridge-*.jar META-INF/MANIFEST.MF
```

## Deploy

> **Do NOT run during a live vehicle diagnostic session.** Deploying requires restarting FDRS, which drops any in-progress VCM3 connection. Always check with the technician at the bench first.

```bash
# After building:
./scripts/deploy.sh                # copies JAR into FDRS bundle/ dir
# ... then manually close & relaunch FDRS ...
```

Verify the bundle activated by tailing FDRS's runtime log (path varies by FDRS version; check `%PROGRAMDATA%\Ford Motor Company\FDRS\` for `*.log`):

```
INFO: futureai-fdrs-layer2-bridge vX.Y.Z starting; bundleId=<N>
```

Or just probe the HTTP server:

```bash
curl http://127.0.0.1:18082/health
# {"ok":true,"bundleVersion":"...","invokerAvailable":true,"commandsExposed":[...]}
```

To roll back:

```bash
rm "/c/Program Files (x86)/Ford Motor Company/FDRS/bundle/futureai-fdrs-layer2-bridge-"*.jar
# ... then restart FDRS ...
```

## Why OSGi

Ford FDRS is built on Apache Felix. Its diagnostic engine exposes ~96 named commands via `com.ford.otx.command.invoker.osgi`'s `OSGICommandInvoker` service — the same dispatcher FDRS's CEF JS-to-Java bridge uses internally. By packaging this bridge as a normal OSGi bundle:

1. We get `Import-Package` access to the invoker without reflection or JVM attach.
2. We're not inventing a new protocol — we're exposing one that already exists.
3. Felix's `felix.auto.deploy.action=install,start` picks up the bundle on restart automatically.

The 350+ FDRS bundles in `bundle/` do not appear signed at the Felix framework level (`config.properties` has no `felix.fileinstall.bundle.signed` setting, no signing provider is wired in), so unsigned third-party bundles install-and-start correctly on FDRS versions current as of 2026-05.

## Safety case

The allowlist is the primary safety boundary. Every command in `CommandAdapterRegistry.java` is read-only or session-metadata. Adding a write-capable command (active tests, calibration writes, key learn) requires:

1. Justification in the adding PR — what the command does, why it's safe, what could go wrong
2. Confirmation that the OEM tool itself exposes the same operation via its UI (we don't expose anything FDRS doesn't already let you do)
3. Explicit out-of-allowlist guard so accidental enablement doesn't broaden the surface

Commands that interact with safety-critical systems (powertrain control mutations, brake/steering interventions) are off-limits without an OEM partnership. Do not add them.

## Example response (bench-verified 2026-05-08)

```bash
$ curl http://127.0.0.1:18082/health
{
  "ok": true,
  "bundleVersion": "0.2.5",
  "invokerAvailable": true,
  "commandsExposed": [
    "getLastSelectedVehicles", "getApplicationsAndSystems", "getVehicleModelStatus",
    "getInstalledMeasurementDevices", "getMeasurementDevice", "listModules",
    "readSelfTestDTCs", "readVehicleHistory", "getCurrentVin",
    "getEngineeringData", "checkNetworkTestRan", "getLastNetworkTestRan",
    "getServiceBulletinsForDTCs", "isCheckVinRequired", "performVehicleIDProcess",
    "getNodeToMdxMapping", "isEvisDataExpired", "readDID"
  ]
}
```

`getApplicationsAndSystems` against a connected F-150 returned in 24ms — well below the latency target for any AI-driven diagnostic flow.

## Origin

Extracted from FutureAI Olympus's runtime (proprietary product) as the bridge layer. The Olympus diagnostic AI itself stays private; this bridge is plumbing.
