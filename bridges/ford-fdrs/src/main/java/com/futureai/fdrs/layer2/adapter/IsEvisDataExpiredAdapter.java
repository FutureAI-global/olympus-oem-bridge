package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.IsEvisDataExpired;
import com.futureai.fdrs.layer2.CommandAdapter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps {@link IsEvisDataExpired} — returns a Boolean indicating
 * whether Ford's EVIS (Engineering Vehicle Information System) data
 * cache has expired for the given VIN.
 *
 * <p>Use case: complements {@link GetEngineeringDataAdapter} (PR#B,
 * #1652/#1662). When Opus needs module version/calibration data, it
 * first checks {@code isEvisDataExpired(vin)}:
 * <ul>
 *   <li>{@code true} → warn tech ("EVIS cache is stale; consider a
 *       vehicle-select refresh for authoritative version data")</li>
 *   <li>{@code false} → trust the cached {@code getEngineeringData()}
 *       result directly</li>
 * </ul>
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor: {@code IsEvisDataExpired(String vin)}.</li>
 *   <li>Queries {@code EvisDataService}. Returns {@code Boolean}.</li>
 *   <li>Read-only: checks cache metadata, no I/O. Olympus tool-
 *       authority tier: {@code "auto"}.</li>
 * </ul>
 *
 * <p>Return shape: wrap Ford's raw Boolean in a named field
 * {@code {evisDataExpired: &lt;bool&gt;, vin: &lt;echoed&gt;}}. Echoing
 * the VIN keeps the tool_result self-describing (Opus doesn't have to
 * correlate back to the request).
 */
public final class IsEvisDataExpiredAdapter implements CommandAdapter {
    @Override public String publicName() { return "isEvisDataExpired"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.IsEvisDataExpired"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(
            "vin",
            "string (required) — VIN whose EVIS cache expiry to check."
        );
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        Object v = args.get("vin");
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            throw new IllegalArgumentException("arg \"vin\" required and must be a non-empty string");
        }
        return new IsEvisDataExpired(((String) v).trim());
    }

    @Override
    public Object serializeResult(Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evisDataExpired", result instanceof Boolean && (Boolean) result);
        return out;
    }
}
