package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetCurrentVin;
import com.futureai.fdrs.layer2.CommandAdapter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps {@link GetCurrentVin} — returns the currently-selected VIN.
 *
 * <p>Complements {@link GetLastSelectedVehiclesAdapter} (which returns a
 * history list). This adapter returns ONLY the active VIN — the one all
 * subsequent bundle commands operate against. Opus uses it to confirm
 * vehicle identity before issuing reads (defense against operator error:
 * tech picked VIN A in FDRS but is asking Opus about VIN B).
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor: no-arg.</li>
 *   <li>Returns: {@code String}. Emitted under a named field
 *       {@code currentVin} for stable envelope shape.</li>
 *   <li>Read-only: reads the VehicleService's in-memory selection.
 *       No VCM3 bus I/O. Olympus tool-authority tier: {@code "auto"}.</li>
 * </ul>
 */
public final class GetCurrentVinAdapter implements CommandAdapter {
    @Override public String publicName() { return "getCurrentVin"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetCurrentVin"; }

    @Override
    public Map<String, String> argShape() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new GetCurrentVin();
    }

    @Override
    public Object serializeResult(Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentVin", result instanceof String ? (String) result : "");
        return out;
    }
}
