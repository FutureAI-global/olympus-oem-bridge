package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.CheckNetworkTestRan;
import com.futureai.fdrs.layer2.CommandAdapter;
import java.util.Map;

/**
 * Wraps {@link CheckNetworkTestRan} — returns a Boolean indicating
 * whether any network test has been run for the currently-selected
 * vehicle.
 *
 * <p>Pairs with {@link GetLastNetworkTestRanAdapter} (both shipped in
 * PR#K). Opus uses this as a cheap precondition probe:
 * "has this vehicle ever been tested?" → if false, the diagnostic
 * flow should nudge the tech to run a baseline network test before
 * attempting symptom-specific reads.
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor: no-arg.</li>
 *   <li>Returns: {@code java.lang.Boolean}. Autoboxed; the walkBean
 *       path handles it as a primitive (renders {@code true} / {@code false}).</li>
 *   <li>Queries {@link com.ford.otx.services.vehicle.VehicleIDService}
 *       — read-only identity-service cache. No VCM3 bus I/O. Olympus
 *       tool-authority tier: {@code "auto"}.</li>
 * </ul>
 *
 * <p>Return shape deviation: we emit
 * {@code {"networkTestEverRan": &lt;bool&gt;}} rather than a bare
 * boolean so the envelope stays uniform with other adapters + Opus'
 * downstream consumer sees a field it can name in its chain-of-thought
 * narration.
 */
public final class CheckNetworkTestRanAdapter implements CommandAdapter {
    @Override public String publicName() { return "checkNetworkTestRan"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.CheckNetworkTestRan"; }

    @Override
    public Map<String, String> argShape() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new CheckNetworkTestRan();
    }

    @Override
    public Object serializeResult(Object result) {
        // Wrap the raw Boolean in a named field so the /commands envelope
        // has a stable shape. walkBean would render the Boolean directly
        // which works, but the named field is easier for Opus to reason
        // about in tool_result narration.
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        boolean ran = result instanceof Boolean && (Boolean) result;
        out.put("networkTestEverRan", ran);
        return out;
    }
}
