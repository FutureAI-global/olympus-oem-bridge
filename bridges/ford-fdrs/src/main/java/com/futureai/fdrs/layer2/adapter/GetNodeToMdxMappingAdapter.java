package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetNodeToMdxMapping;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Map;

/**
 * Wraps {@link GetNodeToMdxMapping} — network topology enumeration.
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50
 * {@code com.ford.otx.services.vehicle-72.34.72.jar}):
 * <ul>
 *   <li>Constructor: no-arg.</li>
 *   <li>Returns: {@code NodeToMdxMapping { Map<String, MdxData> nodeToMdx }}.
 *       Each {@code MdxData { mdxProcUid, altMdx, mdxUid }}. All
 *       primitives/strings + a Map — clean {@code Json.walkBean(_, 6)}
 *       serialization.</li>
 *   <li>Reads cached VehicleService node-to-MDX mapping. <b>Read-only</b>,
 *       no VCM3 I/O on this call (the mapping is populated at vehicle-
 *       select + {@code PopulateMdxRefs} time). Olympus tool-authority
 *       tier: {@code "auto"}.</li>
 * </ul>
 *
 * <p>Chosen over the two alternatives in the plan doc open item:
 * <ul>
 *   <li>{@code GetLocalNodeToMdxMapping} — returns plain
 *       {@code Map<String, String>}, loses the {@code altMdx} flag +
 *       {@code mdxProcUid} distinction. Topology consumers would have
 *       to re-join the richer data elsewhere.</li>
 *   <li>Reconstruct from {@code GetApplicationsAndSystems} — that
 *       command returns Ford {@code Section@hash} toString-strings at
 *       walkBean depth 6 (per Session K's notes on
 *       {@code ListModulesAdapter}), and doesn't surface node-address
 *       keyed topology at all. Mismatched shape.</li>
 * </ul>
 *
 * <p>PR#C / plan doc #1648 track C.
 */
public final class GetNodeToMdxMappingAdapter implements CommandAdapter {
    @Override public String publicName() { return "getNodeToMdxMapping"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetNodeToMdxMapping"; }

    @Override
    public Map<String, String> argShape() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new GetNodeToMdxMapping();
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
