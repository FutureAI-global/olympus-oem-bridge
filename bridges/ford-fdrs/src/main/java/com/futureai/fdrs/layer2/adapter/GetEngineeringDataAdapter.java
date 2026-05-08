package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetEngineeringData;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Map;

/**
 * Wraps {@link GetEngineeringData} for module software/calibration version data.
 *
 * <p>Decision on plan doc scope for PR#B ("module software/calibration
 * versions"):
 *
 * <p>Per-DID composition via PR#A's {@code ReadDID} was the obvious first-
 * take (F186=software version, F188=strategy part, F111=calibration part,
 * F1A2=ASBuilt strategy). But the kickoff comment explicitly notes PR#B
 * does NOT block on A — so the solution must be a dedicated Ford call.
 * {@code GetEngineeringData} is that call:
 *
 * <ul>
 *   <li>{@code EngineeringData { List<EngineeringDataGroup> }} where each
 *       {@code EngineeringDataGroup { name, application, vehicleDataValueList }}
 *       — carries the as-built / version / calibration blocks Ford exposes
 *       through the EVIS (Engineering Vehicle Information System) layer.</li>
 *   <li>No-arg constructor ({@code new GetEngineeringData()}).</li>
 *   <li>Returns a cached blob populated at vehicle-select time + refreshed
 *       by {@code PopulateMdxRefs}. No VCM3 bus I/O on this call; read-only
 *       side-effect class. Olympus tool-authority tier: {@code "auto"}.</li>
 *   <li>Walkbean-serializable at depth 6 (plain POJOs, List, nested beans
 *       with Serializable fields — no Section@hash drift like
 *       GetApplicationsAndSystems suffers).</li>
 * </ul>
 *
 * <p>Empirics from FDRS 15.3.50 jar inspection:
 * <ul>
 *   <li>Command class:
 *       {@code com.ford.otx.services.vehicle.command.GetEngineeringData}
 *       (vehicle-72.34.72.jar).</li>
 *   <li>Return model: {@code com.ford.etis.vehicle.beans.evis.EngineeringData}
 *       (common.core-40.24.26.jar); group model:
 *       {@code com.ford.etis.vehicle.beans.evis.EngineeringDataGroup}.</li>
 * </ul>
 *
 * <p>PR#B / plan doc #1648 track B.
 */
public final class GetEngineeringDataAdapter implements CommandAdapter {
    @Override public String publicName() { return "getEngineeringData"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetEngineeringData"; }

    @Override
    public Map<String, String> argShape() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new GetEngineeringData();
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
