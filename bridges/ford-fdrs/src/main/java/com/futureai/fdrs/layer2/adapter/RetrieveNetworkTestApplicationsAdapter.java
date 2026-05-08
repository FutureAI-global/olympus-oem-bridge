package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.RetrieveNetworkTestApplications;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Map;

/**
 * Wraps {@link RetrieveNetworkTestApplications} — enumerates the network-
 * test diagnostic applications the connected VCM3 supports.
 *
 * <p>Purpose: enables Opus + the tech to see the <b>catalog</b> of available
 * test routines for the currently-selected vehicle BEFORE proposing to run
 * one. Complements PR#E's {@code ActiveTestAdapter} scaffolding: the allow-
 * list gates which routines CAN run; this adapter answers which routines
 * are <b>available</b> in the first place (a subset of allowed × supported).
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor: no-arg.</li>
 *   <li>{@code AbstractCommand<VehicleService, List<Application>, VehicleServiceException>}.</li>
 *   <li>Return model: {@code com.ford.otx.services.application.model.Application}
 *       — same POJO used by {@code GetModuleApplications} (see
 *       {@link GetApplicationsAndSystemsAdapter} pattern notes). Fields:
 *       title, version, applicationType, applicationId, dataIssue,
 *       issueNodes, lastStartDate, etc. walkBean-clean.</li>
 *   <li>Read-only: enumerates cached test-application metadata from the
 *       vehicle's diagnostic package. No VCM3 bus I/O — the catalog is
 *       populated at vehicle-select time. Olympus tool-authority tier:
 *       {@code "auto"}.</li>
 * </ul>
 *
 * <p>Integration note: the application list this returns is the
 * <b>candidate set</b> for any future per-routine adapter wired under
 * {@link com.futureai.fdrs.layer2.activetest.ActiveTestAdapter}. The
 * safety-case doc v1 (PR#E) requires each per-routine PR to provide
 * pre-conditions + watchdog + post-conditions; Opus uses this catalog
 * call to decide whether to escalate a routine request to the tech at
 * all (\"is the routine even offered on this vehicle?\").
 *
 * <p>Beyond the A-G lettered tracks on #1648; shipped as PR#I.
 */
public final class RetrieveNetworkTestApplicationsAdapter implements CommandAdapter {
    @Override public String publicName() { return "retrieveNetworkTestApplications"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.RetrieveNetworkTestApplications"; }

    @Override
    public Map<String, String> argShape() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new RetrieveNetworkTestApplications();
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
