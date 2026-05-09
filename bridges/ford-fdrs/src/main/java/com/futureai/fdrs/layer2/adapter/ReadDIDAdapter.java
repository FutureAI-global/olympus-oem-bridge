package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.comms.command.ReadDID;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps {@link ReadDID} for live per-module PID reads.
 *
 * <p>Empirics (from jar classpath inspection, pending live-vehicle smoke by
 * Session K):
 * <ul>
 *   <li>Constructor: {@code ReadDID(int didNumber, int nodeAddress)}.</li>
 *   <li>Return: {@code com.ford.otx.services.vehicle.comms.model.DID} with
 *       fields {@code id, didType, size, subfields[]}. Each
 *       {@code DIDSubfield} carries {@code id, name, dataType, unit,
 *       hexValue, convertedValue, msb, lsb}. All primitives/strings — clean
 *       {@code Json.walkBean(_, 6)} serialization, no Section@hash drift.</li>
 *   <li>Triggers real module I/O over the VCM3 bus. Write-capable side-
 *       effect class; Olympus tool-authority tier must be {@code "confirm"}
 *       when a TypeScript tool routes to this adapter.</li>
 * </ul>
 *
 * <p><b>2026-05-09 forensic update (Session N bench finding):</b> all 7
 * modules x 7 DIDs returned {@code result:null} on bundle 0.2.5. The same
 * bridge dispatches {@code readSelfTestDTCs} successfully against the same
 * vehicle, so the issue is specific to the {@code ReadDID} command path.
 * Hypotheses ranked by likelihood (per #2484 comment 4411319876):
 * <ol>
 *   <li>JAR adapter drops the response — invoke returns valid DID object
 *       but serializer fails silently.</li>
 *   <li>Diagnostic-session not in extended-mode (UDS 0x10 type 03).
 *       UDS 0x22 (read-DID) requires extended diag on most ECUs;
 *       UDS 0x19 (DTC read) typically allowed in default 0x01.</li>
 *   <li>DID parameter encoding wrong (big-endian vs little-endian, or
 *       single-byte instead of two-byte DID id in the UDS frame).</li>
 * </ol>
 *
 * <p>This file adds reflection-based diagnostic logging in
 * {@link #serializeResult} that dumps: result class + null-status, all
 * getter values when non-null, the originating Command's post-invoke
 * state (in case Ford uses a "store-on-cmd" result pattern). Distinguishes
 * the 3 hypotheses on the next bench session without requiring a wire-
 * level capture.
 *
 * <p>Logging is gated on the system property
 * {@code futureai.layer2.readdid.debug=true} so production runs stay quiet
 * once the diagnosis is closed and this file reverts to its pre-forensic
 * shape.
 *
 * <p>Arg coercion: JSON numbers may arrive as {@code Integer}, {@code Long},
 * or {@code Double} depending on the inbound body. Tech-facing callers may
 * also pass hex strings like {@code "0xF190"} (DID F1 90 for software version
 * block) or {@code "0x760"} (PCM address). The coercer accepts all three
 * shapes and fails loudly on anything outside them so Opus sees a clear
 * arg-shape error instead of silent zero-substitution.
 *
 * <p>PR#A / plan doc #1648 track A — first of the scaling slice. Pairs with
 * PR#D (module-specific PID convenience bundles) which composes this adapter.
 */
public final class ReadDIDAdapter implements CommandAdapter {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.readdid");

    /**
     * Per-thread last-issued ReadDID command. Stored in {@link #make} and
     * read in {@link #serializeResult} so the diagnostic dump can introspect
     * the cmd's post-invoke state. ThreadLocal because Router invokes
     * adapters on the http-request thread; no cross-request leakage.
     */
    private static final ThreadLocal<Command<?, ?, ?>> LAST_CMD = new ThreadLocal<>();

    @Override public String publicName() { return "readDID"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.comms.command.ReadDID"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(
            "didNumber",
            "int (required) — UDS DID identifier. Accepts decimal (61840) or hex string (\"0xF190\"). Typical Ford DIDs: 0xF190=VIN, 0xF1A2=ASBuilt software strategy, 0xF111=calibration part number."
        );
        s.put(
            "nodeAddress",
            "int (required) — module/ECU address on the vehicle bus. Accepts decimal (1888) or hex string (\"0x760\"). Typical Ford addresses: 0x760=PCM, 0x727=BCM, 0x733=ABS, 0x7E0=diagnostic-request broadcast."
        );
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        int didNumber = coerceInt(args, "didNumber");
        int nodeAddress = coerceInt(args, "nodeAddress");
        Command<?, ?, ?> cmd = new ReadDID(didNumber, nodeAddress);
        LAST_CMD.set(cmd);
        if (debugEnabled()) {
            LOG.log(Level.INFO, "[readDID-forensic] make: didNumber=0x{0} ({1}), nodeAddress=0x{2} ({3}), cmd.class={4}",
                new Object[]{
                    Integer.toHexString(didNumber).toUpperCase(),
                    didNumber,
                    Integer.toHexString(nodeAddress).toUpperCase(),
                    nodeAddress,
                    cmd.getClass().getName(),
                });
        }
        return cmd;
    }

    @Override
    public Object serializeResult(Object result) {
        try {
            if (debugEnabled()) {
                dumpForensic(result, LAST_CMD.get());
            }
        } finally {
            LAST_CMD.remove();
        }
        return Json.walkBean(result, 6);
    }

    /**
     * True when {@code -Dfutureai.layer2.readdid.debug=true} is on the JVM
     * cmdline, OR when the env var {@code FUTUREAI_LAYER2_READDID_DEBUG=1}
     * is set. Either gate enables the verbose forensic log path; production
     * runs leave both unset and pay zero cost.
     */
    private static boolean debugEnabled() {
        if ("true".equalsIgnoreCase(System.getProperty("futureai.layer2.readdid.debug"))) return true;
        String env = System.getenv("FUTUREAI_LAYER2_READDID_DEBUG");
        return env != null && (env.equals("1") || env.equalsIgnoreCase("true"));
    }

    /**
     * Reflection-based dump that captures whether the null payload comes
     * from {@code inv.invoke()} returning null (hypothesis 1: serializer
     * fails silently), or returning a populated DID whose getters all
     * return null (hypothesis 2: extended diag session needed), or the
     * cmd object itself carrying state Ford expects callers to read post-
     * invoke (hypothesis 3: store-on-cmd pattern).
     *
     * <p>Output goes to the bundle's standard JUL logger at INFO so it
     * appears alongside the rest of the bridge's startup chatter. Format:
     * one INFO line per dimension so log-grep is straightforward.
     */
    private static void dumpForensic(Object result, Command<?, ?, ?> cmd) {
        // Dimension 1: invoke result class + null status.
        if (result == null) {
            LOG.info("[readDID-forensic] result=NULL (inv.invoke returned null — hypothesis 1 or 3)");
        } else {
            LOG.log(Level.INFO, "[readDID-forensic] result.class={0}", result.getClass().getName());
            // Dimension 2: getter values via reflection. If all values are
            // null, hypothesis 2 (no diag session) is likely. If values are
            // populated but Json.walkBean still gives null, hypothesis 1.
            for (String line : reflectGetters(result, "result")) {
                LOG.info(line);
            }
        }

        // Dimension 3: the originating Command's post-invoke state. Ford's
        // OSGI invoker MAY store the result on the command object itself
        // rather than returning it (the "store-on-cmd" pattern). If a
        // ReadDID instance has a non-null getDID() / getResult() / getValue()
        // post-invoke even when result is null, hypothesis 3 confirmed.
        if (cmd != null) {
            LOG.log(Level.INFO, "[readDID-forensic] cmd.class={0}", cmd.getClass().getName());
            for (String line : reflectGetters(cmd, "cmd")) {
                LOG.info(line);
            }
        } else {
            LOG.info("[readDID-forensic] cmd=NULL (LAST_CMD threadlocal cleared between make/serialize — race?)");
        }
    }

    /**
     * Walk public getters of {@code obj} and return one log-friendly line
     * per getter: {@code   prefix.propName: <value or NULL or EXC>}. Skips
     * Object-class methods and ignores throwables so a single broken
     * getter does not abort the whole dump.
     */
    private static List<String> reflectGetters(Object obj, String prefix) {
        List<String> out = new ArrayList<>();
        if (obj == null) {
            out.add("[readDID-forensic]   " + prefix + ": NULL");
            return out;
        }
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if ((m.getModifiers() & Modifier.STATIC) != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String n = m.getName();
            String prop;
            if (n.startsWith("get") && n.length() > 3 && !"getClass".equals(n)) {
                prop = Character.toLowerCase(n.charAt(3)) + n.substring(4);
            } else if (n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class) {
                prop = Character.toLowerCase(n.charAt(2)) + n.substring(3);
            } else {
                continue;
            }
            String val;
            try {
                Object r = m.invoke(obj);
                if (r == null) val = "NULL";
                else if (r.getClass().isArray() || r instanceof java.util.Collection) {
                    val = r.toString() + " (" + r.getClass().getSimpleName() + ")";
                } else if (r instanceof CharSequence || r instanceof Number || r instanceof Boolean || r instanceof Character) {
                    val = String.valueOf(r);
                } else {
                    val = r.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(r));
                }
            } catch (Throwable t) {
                val = "EXC:" + t.getClass().getSimpleName() + ":" + (t.getMessage() == null ? "" : t.getMessage().replace("\n", " "));
            }
            out.add("[readDID-forensic]   " + prefix + "." + prop + ": " + val);
        }
        return out;
    }

    /**
     * Coerce a JSON-decoded arg value into an int. Accepts:
     * <ul>
     *   <li>{@code Integer} / {@code Long} / {@code Short} / {@code Byte} — narrows to int with range check.</li>
     *   <li>{@code Double} / {@code Float} — only if the fractional part is zero.</li>
     *   <li>{@code String} — decimal (e.g. {@code "61840"}) or hex (e.g. {@code "0xF190"}, {@code "0xf190"}, {@code "F190"}).</li>
     * </ul>
     *
     * @throws IllegalArgumentException when the value is missing, the wrong shape, or out of int range.
     */
    static int coerceInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException("arg \"" + key + "\" required");
        }
        if (v instanceof Integer) {
            return (Integer) v;
        }
        if (v instanceof Long) {
            long l = (Long) v;
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("arg \"" + key + "\" out of int range: " + l);
            }
            return (int) l;
        }
        if (v instanceof Short || v instanceof Byte) {
            return ((Number) v).intValue();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d != Math.floor(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("arg \"" + key + "\" must be an integer, got fractional: " + d);
            }
            if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("arg \"" + key + "\" out of int range: " + d);
            }
            return (int) d;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("arg \"" + key + "\" is an empty string");
            }
            try {
                String lower = s.toLowerCase();
                if (lower.startsWith("0x")) {
                    return Integer.parseInt(lower.substring(2), 16);
                }
                // Bare hex-like strings ("F190") — accept iff all chars are hex and
                // the decimal parse below would fail. This avoids surprising decimal
                // parses like "10" -> 10 (dec) when caller meant 0x10 = 16.
                try {
                    return Integer.parseInt(s, 10);
                } catch (NumberFormatException dec) {
                    if (s.matches("[0-9a-fA-F]+")) {
                        return Integer.parseInt(s, 16);
                    }
                    throw dec;
                }
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                    "arg \"" + key + "\" must be int or hex string, got: " + s
                );
            }
        }
        throw new IllegalArgumentException(
            "arg \"" + key + "\" type not supported: " + v.getClass().getName()
        );
    }
}
