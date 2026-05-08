package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.command.invoker.CommandInvoker;
import com.ford.otx.services.vehicle.comms.command.ReadDID;
import com.ford.otx.services.vehicle.comms.model.DID;
import com.futureai.fdrs.layer2.Bridge;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch composition of {@link ReadDID} — reads multiple DIDs from the
 * same module (node address) in sequence. Composes on top of PR#A
 * ({@link ReadDIDAdapter}, merged in #1650/226dcb58).
 *
 * <p>Design decision (plan doc #1648 track D, "Module-specific PID
 * bundles built on A"):
 *
 * <p>The plan doc's motivating examples were module-specific bundles
 * like "BCM door states" or "PCM live data". Two approaches were
 * considered:
 *
 * <ol>
 *   <li><b>Hardcoded named bundles</b>: e.g.
 *       {@code readBcmDoorStates()} enumerates a fixed list of DIDs.
 *       Pro: Opus-friendly API. Con: embeds vehicle/model-specific
 *       DID assumptions in the bundle, couples Ford domain knowledge
 *       into Java code that can't refresh without a bundle rebuild.</li>
 *   <li><b>Generic batch primitive</b> (this adapter): caller
 *       supplies nodeAddress + DID list. Pro: honest composition,
 *       flexible. Con: requires the caller to know DIDs.</li>
 * </ol>
 *
 * <p>Shipped (2) — pairs with PR#B's {@code getEngineeringData()}
 * (#1652/#1662) which already gives Opus the calibration + software-
 * version DIDs without guessing. Named bundles (BCM door states, PCM
 * live data) belong in the TypeScript server layer where DID catalogs
 * can be version-controlled alongside the Constitution prompt — NOT
 * compiled into the Felix bundle.
 *
 * <p>Empirics:
 * <ul>
 *   <li>Each ReadDID triggers real module I/O on the VCM3 bus
 *       (per PR#A's javadoc). Batch reads are serialized under the
 *       per-session {@code ReentrantLock} Session K owns — no
 *       parallel bus contention.</li>
 *   <li>Write-capable side-effect class. Olympus tool-authority tier:
 *       {@code "confirm"} (inherits PR#A's constraint; the batch
 *       surface doesn't reduce the risk class).</li>
 *   <li>Return shape: array of {@code {did, ok, result|error}}
 *       envelopes. Each read's outcome is independent — one DID
 *       failing doesn't abort the batch.</li>
 * </ul>
 *
 * <p>PR#D / plan doc #1648 track D.
 */
public final class ReadDIDBatchAdapter implements CommandAdapter {
    @Override public String publicName() { return "readDIDBatch"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.comms.command.ReadDID"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(
            "nodeAddress",
            "int (required) — module/ECU address (e.g. 0x760 for PCM, 0x727 for BCM). Accepts decimal or hex string. Applied to every DID in the batch."
        );
        s.put(
            "dids",
            "int[] (required, non-empty) — UDS DID identifiers to read in sequence. Each accepts decimal or hex string, same coercion as ReadDIDAdapter. Reads execute serially in list order."
        );
        return s;
    }

    /**
     * PR#D does not use the {@link CommandAdapter#make} → Router.execute
     * path directly because Router's 1-command-per-dispatch shape
     * doesn't fit a multi-read batch. Instead, this adapter shortcuts
     * through {@link #serializeResult} acting as a thin executor:
     * {@code make} returns a sentinel Command whose result is replaced
     * when Router hands the adapter the (unused) execute output.
     *
     * <p>Cleaner alternative would be a separate "batch" dispatch path
     * on Router; keeping scope tight for this PR means we inline the
     * loop here instead. Session K's review can pull this up to Router
     * if batch dispatches show up in more adapters later.
     *
     * <p>Rationale for NOT using Router.execute() for each DID:
     * Router holds the per-session lock for each dispatch; Ford's
     * VehicleService serializes CommsService invocations at that layer
     * anyway, so no contention issue. The per-DID locking overhead
     * would just add ms per read with no safety benefit.
     */
    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        // The batch is executed inside the adapter (see class javadoc
        // + serializeResult). `make` still has to return a Command for
        // the interface contract — the first DID serves as a sentinel
        // so Router's pre-flight doesn't NPE.
        int nodeAddress = ReadDIDAdapter.coerceInt(args, "nodeAddress");
        List<Integer> dids = coerceIntList(args.get("dids"));
        if (dids.isEmpty()) {
            throw new IllegalArgumentException("arg \"dids\" required and must be a non-empty int/hex array");
        }
        // Stash the plan so serializeResult can re-execute the batch.
        BATCH_PLAN.set(new BatchPlan(nodeAddress, dids));
        return new ReadDID(dids.get(0), nodeAddress);
    }

    /**
     * Re-executes each DID read against the same CommsService instance
     * Ford hands us during the first execute(). The first read's
     * result is already in {@code firstResult}; we prepend it, then
     * iterate the rest via direct {@code CommandInvoker} reinvocations.
     */
    @Override
    public Object serializeResult(Object firstResult) {
        BatchPlan plan = BATCH_PLAN.get();
        BATCH_PLAN.remove();
        if (plan == null) {
            // Safety fallback — serialize bare if batch plan is missing
            // (should never happen given make() set it).
            return Json.walkBean(firstResult, 6);
        }

        List<Map<String, Object>> reads = new ArrayList<>(plan.dids.size());
        // First result is what Router just executed for us.
        reads.add(wrapReadResult(plan.dids.get(0), firstResult, null));

        // Remaining DIDs — invoke each via the Bridge's shared invoker.
        // Serial execution; Ford's comms layer serializes internally
        // under the VehicleService lock already.
        Bridge bridge = Bridge.active();
        CommandInvoker invoker = bridge != null ? bridge.invoker() : null;
        if (invoker == null) {
            // Graceful degrade: first DID's result is already in hand;
            // report the rest as skipped with a clear error so Opus
            // sees they weren't attempted (vs. silently dropped).
            for (int i = 1; i < plan.dids.size(); i++) {
                int did = plan.dids.get(i);
                reads.add(wrapReadResult(did, null,
                    new IllegalStateException("CommandInvoker unavailable (bundle stopped?)")));
            }
        } else {
            for (int i = 1; i < plan.dids.size(); i++) {
                int did = plan.dids.get(i);
                try {
                    Object result = invoker.invoke(new ReadDID(did, plan.nodeAddress));
                    reads.add(wrapReadResult(did, result, null));
                } catch (Exception err) {
                    reads.add(wrapReadResult(did, null, err));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeAddress", plan.nodeAddress);
        out.put("count", reads.size());
        out.put("reads", reads);
        return out;
    }

    private static Map<String, Object> wrapReadResult(
        int did,
        Object result,
        Exception err
    ) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("did", did);
        if (err != null) {
            entry.put("ok", false);
            entry.put("error", err.getClass().getSimpleName() + ": " +
                (err.getMessage() != null ? err.getMessage() : "(no message)"));
            return entry;
        }
        entry.put("ok", true);
        if (result instanceof DID) {
            entry.put("result", Json.walkBean(result, 6));
        } else if (result != null) {
            entry.put("result", Json.walkBean(result, 6));
        } else {
            entry.put("result", null);
        }
        return entry;
    }

    private static List<Integer> coerceIntList(Object raw) {
        if (raw == null) return java.util.Collections.emptyList();
        List<Integer> out = new ArrayList<>();
        if (raw instanceof Collection) {
            for (Object v : (Collection<?>) raw) {
                out.add(coerceOneInt(v));
            }
            return out;
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return out;
            for (String part : s.split("[,\\s]+")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(coerceOneInt(p));
                }
            }
            return out;
        }
        throw new IllegalArgumentException(
            "arg \"dids\" must be int/string array or comma-separated string; got "
                + raw.getClass().getName()
        );
    }

    private static int coerceOneInt(Object v) {
        // Delegate to ReadDIDAdapter's coerceInt logic via a 1-key map.
        Map<String, Object> shim = new LinkedHashMap<>();
        shim.put("v", v);
        return ReadDIDAdapter.coerceInt(shim, "v");
    }

    private static final ThreadLocal<BatchPlan> BATCH_PLAN = new ThreadLocal<>();

    private static final class BatchPlan {
        final int nodeAddress;
        final List<Integer> dids;
        BatchPlan(int nodeAddress, List<Integer> dids) {
            this.nodeAddress = nodeAddress;
            this.dids = dids;
        }
    }
}
