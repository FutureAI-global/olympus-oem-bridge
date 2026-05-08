package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetLastNetworkTestRan;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps {@link GetLastNetworkTestRan} — returns the timestamp of the
 * last network-test run for a given VIN.
 *
 * <p>Pairs with {@link CheckNetworkTestRanAdapter} (both shipped in
 * PR#K). Opus uses this to reason about diagnostic freshness:
 * "last test was 14 months ago, historical DTCs may be stale" vs.
 * "test ran this morning, DTCs are current."
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor: {@code GetLastNetworkTestRan(String vin)}.</li>
 *   <li>Returns: {@code java.util.Date}. Walkbean handles it via
 *       standard java.util.Date serialization (toString/long epoch).</li>
 *   <li>Queries {@link com.ford.otx.services.vehicle.VehicleIDService}
 *       — a read-only identity-service cache. No VCM3 bus I/O on this
 *       call. Olympus tool-authority tier: {@code "auto"}.</li>
 * </ul>
 *
 * <p>PR#K / beyond A-G lettered tracks — pair with
 * CheckNetworkTestRanAdapter.
 */
public final class GetLastNetworkTestRanAdapter implements CommandAdapter {
    @Override public String publicName() { return "getLastNetworkTestRan"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetLastNetworkTestRan"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(
            "vin",
            "string (required) — VIN whose last network-test timestamp to return. Ford's VehicleIDService caches this per-VIN; no I/O cost."
        );
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        Object v = args.get("vin");
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            throw new IllegalArgumentException("arg \"vin\" required and must be a non-empty string");
        }
        return new GetLastNetworkTestRan(((String) v).trim());
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
