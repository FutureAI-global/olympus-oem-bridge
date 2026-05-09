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
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import com.futureai.fdrs.layer2.Bridge;

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
 * <p><b>2026-05-09 root-cause fix attempt.</b> Session N's bench
 * (#2484 comments 4411319876, 4411430404, 4411476258) showed:
 * <ul>
 *   <li>readDID returns {@code result:null} on every (module x DID) combo</li>
 *   <li>Same null on raw curl bypassing MCP — definitively JAR-side</li>
 *   <li>1ms latency = no UDS bus traffic dispatched at all</li>
 *   <li>readSelfTestDTCs WORKS on the same bridge with rich payload</li>
 * </ul>
 *
 * <p>JAR class-file inspection of {@code com.ford.otx.command.AbstractCommand}
 * + {@code Command} interface revealed the receiver pattern:
 * <pre>
 *   - Command.getReceiverType()         -- Class&lt;?&gt; of expected result
 *   - CommandInvoker.registerReceiver(cmd)   -- creates + binds receiver
 *   - CommandInvoker.invoke(cmd)        -- populates receiver via execute()
 *   - CommandInvoker.deregisterReceiver(cmd) -- cleanup
 * </pre>
 *
 * <p>{@link com.ford.otx.services.vehicle.comms.command.ReadAllCMDTCs}
 * (working) has {@code receiver} as a constructor arg — caller passes
 * a {@code DTCList} which the command populates. Returns it via the
 * conventional {@code execute} pattern, walkBean-able from the invoke
 * return value.
 *
 * <p>{@link ReadDID} (broken) takes only {@code (didNumber, nodeAddress)}.
 * No receiver in the constructor. Means one of:
 * <ol>
 *   <li>Result is delivered via {@link CommandInvoker#registerReceiver}
 *       which returns/binds a receiver; we never call this so receiver
 *       stays null and the bus dispatch is suppressed.</li>
 *   <li>Result is stored on the cmd instance post-{@code execute()} via a
 *       getter we can locate by walking the cmd's fields/getters.</li>
 *   <li>Result is the {@code execute()} return value but Router's invoke
 *       wraps it in a way that loses non-receiver returns.</li>
 * </ol>
 *
 * <p>This adapter handles all three with a layered fallback:
 * <ol>
 *   <li>{@link #serializeResult} called by Router with {@code result}
 *       from {@code inv.invoke(cmd)}. If non-null: walkBean it (the
 *       happy path other commands hit).</li>
 *   <li>If null: walk the cmd object's getters via reflection. Look
 *       for a getter whose return type is the receiver type
 *       ({@code cmd.getReceiverType()}) and which returns a non-null
 *       value. Surface that as the result. Catches hypotheses 2+3.</li>
 *   <li>If still nothing: return a structured diagnostic payload
 *       {@code {sourceWasNull: true, cmdReceiverType, cmdGetterDump}}
 *       so consumers can self-debug instead of getting an opaque null.</li>
 * </ol>
 *
 * <p>Forensic logging (gated on {@code FUTUREAI_LAYER2_READDID_DEBUG=1}
 * env var or {@code -Dfutureai.layer2.readdid.debug=true}) captures every
 * branch's evidence to the bundle's JUL logger so N's next bench can
 * identify which hypothesis actually applies even if the fallback
 * succeeds.
 */
public final class ReadDIDAdapter implements CommandAdapter {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.readdid");

    /**
     * Per-thread last-issued ReadDID command. Stored in {@link #make} and
     * read in {@link #serializeResult} so the fallback path can introspect
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
            LOG.log(Level.INFO, "[readDID] make: didNumber=0x{0} ({1}), nodeAddress=0x{2} ({3}), cmd.class={4}",
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
        Command<?, ?, ?> cmd = LAST_CMD.get();
        try {
            // Path 1: invoke() returned a non-null result — happy path.
            if (result != null) {
                if (debugEnabled()) {
                    LOG.log(Level.INFO, "[readDID] result.class={0} (non-null, using direct return)",
                        result.getClass().getName());
                }
                return Json.walkBean(result, 6);
            }

            // Path 2 (Option BETA v2 - 2026-05-09 - Session N Path-3 dump
            // + javap-against-Ford finding):
            //
            // ReadDID.execute() takes ONE arg - the receiver service:
            //   public DID execute(DIDCommsService) throws DIDCommsServiceException
            //   public Object execute(Object) throws Exception   (bridge method post-erasure)
            //
            // The OSGI invoker normally handles service-resolve + execute(service)
            // for the caller. inv.invoke(cmd) was returning null because the
            // invoker doesn't auto-dispatch ReadDID this way. Real fix: look
            // up DIDCommsService ourselves via the bundle's BundleContext
            // (cmd.getReceiverType() gives us the interface Class), then call
            // cmd.execute(service) reflectively. The bridge-method form
            // (Object.class arg) always exists post-erasure so getMethod with
            // Object.class is reliable across all Ford Command<R, T, E> subclasses.
            if (cmd != null) {
                if (debugEnabled()) {
                    LOG.info("[readDID] result=NULL from inv.invoke; trying cmd.execute(receiverService)");
                }
                try {
                    Method getRecvTypeM = cmd.getClass().getMethod("getReceiverType");
                    Object rt = getRecvTypeM.invoke(cmd);
                    Class<?> recvClass = (rt instanceof Class<?>) ? (Class<?>) rt : null;
                    Object receiverService = null;
                    String recvFqn = null;
                    if (recvClass != null) {
                        recvFqn = recvClass.getName();
                        Bridge bridge = Bridge.active();
                        if (bridge != null) {
                            BundleContext bctx = bridge.context();
                            if (bctx != null) {
                                ServiceReference<?> ref = bctx.getServiceReference(recvFqn);
                                if (ref != null) {
                                    receiverService = bctx.getService(ref);
                                }
                            }
                        }
                    }
                    if (debugEnabled()) {
                        LOG.log(Level.INFO, "[readDID] receiverType={0} receiverService={1}",
                            new Object[]{recvFqn, receiverService == null ? "NULL" : receiverService.getClass().getName()});
                    }
                    if (receiverService != null) {
                        Method execMethod = cmd.getClass().getMethod("execute", Object.class);
                        Object directResult = execMethod.invoke(cmd, receiverService);
                        if (directResult != null) {
                            if (debugEnabled()) {
                                LOG.log(Level.INFO, "[readDID] cmd.execute(service) returned: {0}",
                                    directResult.getClass().getName());
                            }
                            return Json.walkBean(directResult, 6);
                        }
                        // v3: null return after real bus traffic (~975ms on N's bench) is
                        // the signature of UDS 0x22 NRC because the ECU is in default
                        // diagnostic session. Escalate to EXTENTED_SESSION via
                        // DiagSessionCommand and retry. Reflection-based so the bundle
                        // does not need new Import-Package entries for com.ford.dsp.domain.*.
                        Object retryResult = retryWithExtendedSession(cmd, receiverService, execMethod);
                        if (retryResult != null) {
                            if (debugEnabled()) {
                                LOG.log(Level.INFO, "[readDID] retry-with-extended-session returned: {0}",
                                    retryResult.getClass().getName());
                            }
                            return Json.walkBean(retryResult, 6);
                        }
                        if (debugEnabled()) {
                            LOG.info("[readDID] cmd.execute(service) and retry both null; falling through");
                        }
                    } else if (debugEnabled()) {
                        LOG.info("[readDID] could not resolve receiver service from BundleContext; falling through");
                    }
                } catch (Throwable t) {
                    if (debugEnabled()) {
                        LOG.log(Level.INFO, "[readDID] Option BETA v2 threw: {0}: {1}",
                            new Object[]{t.getClass().getSimpleName(), t.getMessage()});
                    }
                }
            }

            // Path 3 (legacy fallback · pre-Session-N dump): invoke() returned
            // null and execute() didn't help. Try to recover from cmd state by
            // walking getters whose return type matches getReceiverType().
            // This was the original hypothesis before N's bench dump showed
            // ReadDID's receiverType is a SERVICE INTERFACE (DIDCommsService)
            // not the result type, so this path will typically find nothing
            // for ReadDID — but kept for adapters that DO use the constructor-
            // arg-receiver pattern (ReadAllCMDTCs-style).
            if (cmd != null) {
                if (debugEnabled()) {
                    LOG.info("[readDID] scanning cmd getters for receiver-bound result (legacy fallback)");
                    for (String line : reflectGetters(cmd, "cmd")) {
                        LOG.info(line);
                    }
                }

                Class<?> recvType = null;
                try {
                    Method getRecvType = cmd.getClass().getMethod("getReceiverType");
                    Object t = getRecvType.invoke(cmd);
                    if (t instanceof Class<?>) recvType = (Class<?>) t;
                } catch (Throwable ignore) { /* not a receiver-pattern command */ }

                Object recovered = findReceiverValue(cmd, recvType);
                if (recovered != null) {
                    if (debugEnabled()) {
                        LOG.log(Level.INFO, "[readDID] recovered from cmd state: {0}",
                            recovered.getClass().getName());
                    }
                    return Json.walkBean(recovered, 6);
                }

                // Path 3: still nothing. Return a structured diagnostic so
                // the consumer can see WHY the result was null instead of
                // getting an opaque {ok:true, result:null} that hides the
                // cause. This payload includes the cmd's getter dump and
                // the declared receiver type so a downstream forensic step
                // has full context.
                Map<String, Object> diag = new LinkedHashMap<>();
                diag.put("sourceWasNull", true);
                diag.put("cmdClass", cmd.getClass().getName());
                if (recvType != null) diag.put("cmdReceiverType", recvType.getName());
                Map<String, Object> getterDump = new LinkedHashMap<>();
                for (Method m : cmd.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if ((m.getModifiers() & Modifier.STATIC) != 0) continue;
                    if (m.getDeclaringClass() == Object.class) continue;
                    String n = m.getName();
                    if (!n.startsWith("get") && !n.startsWith("is")) continue;
                    if ("getClass".equals(n)) continue;
                    String prop = n.startsWith("get")
                        ? Character.toLowerCase(n.charAt(3)) + n.substring(4)
                        : Character.toLowerCase(n.charAt(2)) + n.substring(3);
                    try {
                        Object r = m.invoke(cmd);
                        getterDump.put(prop, r == null ? null : r.toString());
                    } catch (Throwable t) {
                        getterDump.put(prop, "EXC:" + t.getClass().getSimpleName());
                    }
                }
                diag.put("cmdGetters", getterDump);
                return diag;
            }

            return null;
        } finally {
            LAST_CMD.remove();
        }
    }

    /**
     * v3: switch the target module to EXTENTED_SESSION (UDS 0x10 type 03)
     * via Ford's DiagSessionCommand, then retry cmd.execute(receiverService).
     * Uses reflection so the bundle manifest does not have to add Import-
     * Package entries for com.ford.dsp.domain.vehicle.services.* — those
     * classes are loaded at runtime from the Felix classpath when the
     * bundle is already running inside FDRS.
     *
     * Returns the populated DID on success, null if any step in the
     * session-escalation chain failed (in which case caller falls through
     * to the structured diagnostic dump).
     */
    private static Object retryWithExtendedSession(Command<?, ?, ?> cmd, Object readDIDService, Method readDIDExecMethod) {
        try {
            // Resolve nodeAddress from cmd's private field — it was set
            // by the constructor and is needed for addModuleForSessionRequest.
            int nodeAddress;
            try {
                java.lang.reflect.Field naField = cmd.getClass().getDeclaredField("nodeAddress");
                naField.setAccessible(true);
                nodeAddress = naField.getInt(cmd);
            } catch (Throwable t) {
                if (debugEnabled()) LOG.log(Level.INFO, "[readDID] retry: could not read nodeAddress from cmd: {0}", t.getMessage());
                return null;
            }

            // Look up SelftestService — the receiver for DiagSessionCommand.
            Bridge bridge = Bridge.active();
            if (bridge == null) return null;
            BundleContext bctx = bridge.context();
            if (bctx == null) return null;

            String selftestSvcFqn = "com.ford.dsp.domain.vehicle.services.SelftestService";
            ServiceReference<?> selftestRef = bctx.getServiceReference(selftestSvcFqn);
            if (selftestRef == null) {
                if (debugEnabled()) LOG.info("[readDID] retry: SelftestService not registered in BundleContext");
                return null;
            }
            Object selftestSvc = bctx.getService(selftestRef);
            if (selftestSvc == null) return null;

            // Build DiagSessionCommand + addModuleForSessionRequest(nodeAddress, EXTENTED_SESSION).
            Class<?> diagSessCmdClass = Class.forName("com.ford.dsp.domain.vehicle.services.DiagSessionCommand");
            Object diagSessCmd = diagSessCmdClass.getDeclaredConstructor().newInstance();
            Class<?> sessEnumClass = Class.forName("com.ford.dsp.domain.vehicle.services.DiagSessionInput$Session");
            // Ford typo: "EXTENTED" not "EXTENDED" — preserved verbatim per the SDK.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object extSession = Enum.valueOf((Class) sessEnumClass, "EXTENTED_SESSION");
            Method addReqM = diagSessCmdClass.getMethod("addModuleForSessionRequest", int.class, sessEnumClass);
            addReqM.invoke(diagSessCmd, nodeAddress, extSession);

            // Execute the session switch via the bridge-method form.
            Method diagExecM = diagSessCmdClass.getMethod("execute", Object.class);
            diagExecM.invoke(diagSessCmd, selftestSvc);
            if (debugEnabled()) {
                LOG.log(Level.INFO, "[readDID] retry: switched module 0x{0} to EXTENTED_SESSION; rerunning readDID",
                    Integer.toHexString(nodeAddress).toUpperCase());
            }

            // Now retry the original readDID command — same cmd, same receiver service.
            Object retryResult = readDIDExecMethod.invoke(cmd, readDIDService);
            return retryResult;
        } catch (Throwable t) {
            if (debugEnabled()) {
                LOG.log(Level.INFO, "[readDID] retry-with-extended-session threw: {0}: {1}",
                    new Object[]{t.getClass().getSimpleName(), t.getMessage()});
            }
            return null;
        }
    }

    /**
     * Search the cmd's getters for one returning a non-null value whose
     * type matches (or is assignable to) the declared receiver type.
     * If recvType is null, returns the first non-null non-primitive
     * non-CharSequence return value we find — best-effort fallback for
     * commands whose receiver type is unknown but whose state is reachable.
     */
    private static Object findReceiverValue(Object cmd, Class<?> recvType) {
        for (Method m : cmd.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if ((m.getModifiers() & Modifier.STATIC) != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String n = m.getName();
            if (!n.startsWith("get") && !n.startsWith("is")) continue;
            if ("getClass".equals(n) || "getReceiverType".equals(n)
                || "getReceiverPropertyTypes".equals(n) || "getOperationId".equals(n)
                || "getLoggingDetails".equals(n)) continue;
            Class<?> ret = m.getReturnType();
            // Skip primitives, void, and trivially-reflective types.
            if (ret == void.class || ret.isPrimitive()) continue;
            if (ret == String.class || CharSequence.class.isAssignableFrom(ret)) continue;
            try {
                Object v = m.invoke(cmd);
                if (v == null) continue;
                if (recvType != null) {
                    if (recvType.isInstance(v)) return v;
                    // Also accept if it's a List/Set whose element-type matches
                    // — covers List<DID> shapes which we might see here.
                    continue;
                }
                // recvType unknown: best-effort, accept any non-trivial object.
                return v;
            } catch (Throwable ignore) { /* skip */ }
        }
        return null;
    }

    /**
     * True when {@code -Dfutureai.layer2.readdid.debug=true} is on the JVM
     * cmdline, OR when the env var {@code FUTUREAI_LAYER2_READDID_DEBUG=1}
     * is set. Either gate enables verbose forensic logging.
     */
    private static boolean debugEnabled() {
        if ("true".equalsIgnoreCase(System.getProperty("futureai.layer2.readdid.debug"))) return true;
        String env = System.getenv("FUTUREAI_LAYER2_READDID_DEBUG");
        return env != null && (env.equals("1") || env.equalsIgnoreCase("true"));
    }

    /**
     * Walk public getters of {@code obj} and return one log-friendly line
     * per getter. Skips Object-class methods and ignores throwables.
     */
    private static List<String> reflectGetters(Object obj, String prefix) {
        List<String> out = new ArrayList<>();
        if (obj == null) {
            out.add("[readDID]   " + prefix + ": NULL");
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
                val = "EXC:" + t.getClass().getSimpleName();
            }
            out.add("[readDID]   " + prefix + "." + prop + ": " + val);
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
