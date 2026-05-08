package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.IsCheckVinRequired;
import com.futureai.fdrs.layer2.CommandAdapter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps {@link IsCheckVinRequired} — returns a Boolean indicating
 * whether Ford's VIN validation step is required before proceeding
 * with diagnostic reads.
 *
 * <p>Use case: Opus sees {@code checkVinRequired: true} and surfaces a
 * pre-flight to the tech ("confirm VIN via PTS lookup before reading
 * this vehicle — Ford policy flag is set"). When {@code false}, Opus
 * can skip the VIN-verification nag.
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor: no-arg.</li>
 *   <li>Queries {@code VinCheck} service. Returns {@code Boolean}.</li>
 *   <li>Read-only: no VCM3 bus I/O. Olympus tool-authority tier:
 *       {@code "auto"}.</li>
 * </ul>
 *
 * <p>Return shape: wrap Ford's raw Boolean in a named field
 * {@code {checkVinRequired: &lt;bool&gt;}} for uniform envelope +
 * Opus tool_result narration clarity.
 */
public final class IsCheckVinRequiredAdapter implements CommandAdapter {
    @Override public String publicName() { return "isCheckVinRequired"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.IsCheckVinRequired"; }

    @Override
    public Map<String, String> argShape() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new IsCheckVinRequired();
    }

    @Override
    public Object serializeResult(Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkVinRequired", result instanceof Boolean && (Boolean) result);
        return out;
    }
}
