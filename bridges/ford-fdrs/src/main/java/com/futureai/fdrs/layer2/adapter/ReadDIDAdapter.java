package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.comms.command.ReadDID;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.LinkedHashMap;
import java.util.Map;

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
        return new ReadDID(didNumber, nodeAddress);
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
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
