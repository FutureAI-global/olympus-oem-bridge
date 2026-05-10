package com.futureai.fdrs.layer2.session;

import com.ford.otx.command.invoker.CommandInvoker;
import com.ford.otx.services.user.command.LoginUser;
import com.ford.otx.services.vehicle.command.SelectVehicle;
import com.ford.otx.services.vehicle.comms.command.ReadDID;
import com.futureai.fdrs.layer2.Bridge;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives the minimal sequence that puts the Ford OSGi stack into a
 * "vehicle-selected" state so downstream read commands succeed.
 *
 * <p>Chain (Session E signed on PR #1587 · 05:38:14Z ·
 * {@link com.futureai.fdrs.layer2.adapter.ReadSelfTestDTCsAdapter} / Part-1 §5):
 *
 * <ol>
 *   <li>{@code LoginUser(Map<String,String>)} — skipped if request omits
 *       {@code user} (Felix classloader inherits PATS auth from the running
 *       FDRS HMI in the local-VCM3 case).</li>
 *   <li>{@code SelectVehicle(String vin)} — populates VehicleService with the
 *       target VIN. Required.</li>
 * </ol>
 *
 * <p>Plan's wider chain (LoginUser → Authenticate → SelectVehicle →
 * SubmitVehicle → PopulateMdxRefs → RetrieveDataCollectionApplications) is
 * intentionally truncated. Part-2 empirics on #1587 will validate whether
 * {@code listModules} / {@code readSelfTestDTCs} / {@code readVehicleHistory}
 * succeed after these 2 steps alone. If they don't, the chain expands in a
 * follow-up. The ghost step ({@code Authenticate}) was removed — no such
 * class exists in the FDRS install (see #1569 FQCN audit).
 */
public final class BootstrapAdapter {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.bootstrap");

    private final SessionManager sessions;
    private final Bridge bridge;

    public BootstrapAdapter(SessionManager sessions, Bridge bridge) {
        this.sessions = sessions;
        this.bridge = bridge;
    }

    /**
     * Executes the chain and registers a session. Returns a
     * JSON-serializable result map matching the plan's
     * {@code /session/bootstrap} response shape.
     *
     * @throws IllegalArgumentException if {@code vin} missing/empty
     * @throws IllegalStateException if the invoker service isn't bound yet
     * @throws Exception any Ford command exception, surfaced via
     *         Router as {@code kind:"invocation_error"}
     */
    public Map<String, Object> bootstrap(Map<String, Object> body) throws Exception {
        Object vinArg = body.get("vin");
        if (!(vinArg instanceof String) || ((String) vinArg).isEmpty()) {
            throw new IllegalArgumentException("vin (non-empty string) is required");
        }
        String vin = (String) vinArg;

        CommandInvoker invoker = bridge.invoker();
        if (invoker == null) {
            throw new IllegalStateException(
                "CommandInvoker service not yet bound in Felix registry");
        }

        long t0 = System.currentTimeMillis();
        boolean authenticated = false;

        Object userArg = body.get("user");
        if (userArg instanceof Map) {
            Map<String, String> userMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) userArg).entrySet()) {
                userMap.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            if (!userMap.isEmpty()) {
                invoker.invoke(new LoginUser(userMap));
                authenticated = true;
            }
        }

        invoker.invoke(new SelectVehicle(vin));

        // Cold-start warm-up. Ford's diagnostic session needs a no-op
        // tester-present read to settle before the first user-facing
        // readDID will return populated. N's bench (PR #2484 comment
        // 4413851004) showed the FIRST readDID after SelectVehicle can
        // return null even on known-safe (module, DID) pairs, while the
        // SECOND read on the same pair succeeds. Discarded warm-up read
        // here makes first-call deterministic.
        //
        // Best-effort. If the warm-up read fails for any reason we still
        // report bootstrap success and the user's first read just hits
        // the same cold-start condition we were trying to avoid; we
        // don't want a transient warm-up failure to take down session
        // establishment.
        //
        // Choice of (BCM 0x726, VIN 0xF190): empirically validated
        // present on Ranger 2022 + most Ford configurations. VIN is a
        // global identifier every Ford module exposes via BCM.
        boolean warmupOk = false;
        try {
            Object warmup = invoker.invoke(new ReadDID(0x726, 0xF190));
            warmupOk = warmup != null;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "bootstrap warmup readDID threw (ignored): {0}", t.getMessage());
        }

        String sessionId = sessions.createSession();
        SessionManager.SessionState s = sessions.acquire(sessionId);
        try {
            if (s != null) s.vin = vin;
        } finally {
            sessions.release(s);
        }
        LOG.info("bootstrap ok: sessionId=" + sessionId + " vin=" + vin
            + " authenticated=" + authenticated + " warmup=" + warmupOk);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", sessionId);
        out.put("vin", vin);
        out.put("authenticated", authenticated);
        out.put("warmup", warmupOk);
        out.put("latencyMs", System.currentTimeMillis() - t0);
        return out;
    }
}
