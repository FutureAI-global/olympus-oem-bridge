package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.PerformVehicleIDProcess;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Collections;
import java.util.Map;

/**
 * Wraps {@link PerformVehicleIDProcess} — triggers FDRS's Vehicle ID routine.
 *
 * <p><b>Why this adapter exists:</b> Without Vehicle ID, the modules on the
 * vehicle bus stay in {@code offline=true} state and {@code readSelfTestDTCs}
 * / {@code listModules} return empty caches regardless of whether real DTCs
 * exist on the vehicle. FDRS HMI's "Run Vehicle ID" button triggers this
 * command; exposing it to the bundle lets the Layer 2 CLI call it
 * autonomously on session start instead of requiring tech button-clicks.
 *
 * <p><b>Empirical state today (2026-04-23, live-smoke on VIN 1FTRF3AT4TEC85082
 * "2026 F-Super Duty 6.7L diesel"):</b> FDRS HMI had the vehicle paired
 * (profile loaded, 11 complex tools visible) but with "Odometer: Not Read"
 * and every tool reporting {@code offline=true}. Bundle calls to
 * {@code readSelfTestDTCs} and {@code listModules} returned empty — no bus
 * scan had occurred. This adapter fills that gap.
 *
 * <p><b>Side-effect class:</b> Write-capable — performs live bus I/O against
 * every module to enumerate presence, read VINs, populate EVIS caches. Not
 * destructive (no DTC clears, no actuator actions, no flashes), but it DOES
 * initiate real CAN traffic.
 *
 * <p><b>Tool-authority tier:</b> {@code "confirm"}. The tech should see FDRS
 * UI activity when this fires (status bar updates, module icons populating).
 * Not auto-tier because it's a multi-module bus sweep, not a cached read.
 *
 * <p>No-arg constructor per jar-classpath inspection
 * ({@code com.ford.otx.services.vehicle-72.34.72.jar}). Return model is
 * Ford's vehicle-session state after ID completes; walkBean serializable at
 * depth 6 with standard POJO fields.
 *
 * <p>K · unblocks the {@code Odometer: Not Read} smoke state observed on
 * #1617 live-smoke 2026-04-23; pairs with Session C's #1667 status probes
 * (when was Vehicle ID last run?).
 */
public final class PerformVehicleIDProcessAdapter implements CommandAdapter {
    @Override public String publicName() { return "performVehicleID"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.PerformVehicleIDProcess"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new java.util.LinkedHashMap<>();
        s.put("vin", "string (required) — VIN to perform Vehicle ID against. Caller's responsibility to provide; the CLI resolves from the selected vehicle via getVehicleModelStatus / getLastSelectedVehicles.");
        s.put("force", "boolean (optional, default false) — when true, re-runs Vehicle ID even if it previously completed for this VIN in this session.");
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        Object vinArg = args.get("vin");
        if (!(vinArg instanceof String) || ((String) vinArg).trim().isEmpty()) {
            throw new IllegalArgumentException("arg \"vin\" required (string)");
        }
        String vin = ((String) vinArg).trim();
        Object forceArg = args.get("force");
        boolean force = forceArg instanceof Boolean && (Boolean) forceArg;
        return new PerformVehicleIDProcess(vin, force);
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
