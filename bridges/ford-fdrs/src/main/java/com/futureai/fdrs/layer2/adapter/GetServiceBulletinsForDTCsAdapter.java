package com.futureai.fdrs.layer2.adapter;

import com.ford.etis.vehicle.beans.evis.DTC;
import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.Article;
import com.ford.otx.services.vehicle.command.GetServiceBulletinsForDTCs;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps {@link GetServiceBulletinsForDTCs} — per-DTC TSB lookup directly
 * from the bundle's {@code ServiceBulletinService}.
 *
 * <p>Complements the server-side {@code search-wsm-tsb} TypeScript tool
 * (Session I · PR#2x family) which queries the {@code FordPTSData}
 * DB corpus. This adapter reads TSBs straight from Ford's
 * ServiceBulletinService bundle, giving Opus a second grounding path
 * that works even when the DB corpus is sparse for a particular VIN or
 * recently-released DTC.
 *
 * <p>Empirics (from jar classpath inspection against FDRS 15.3.50):
 * <ul>
 *   <li>Constructor:
 *       {@code GetServiceBulletinsForDTCs(List<com.ford.etis.vehicle.beans.evis.DTC>)}.</li>
 *   <li>Returns: {@code Map<DTC, List<Article>>}. Each
 *       {@code Article { articleID, title, dtcs, type }} — all
 *       primitives/strings + a List — walkBean-clean.</li>
 *   <li>DTC bean has no-arg ctor + {@code setDtcId(String)} setter.
 *       The adapter constructs minimal DTC beans from raw code strings
 *       (e.g. {@code "P0300"}) — sufficient for the SB lookup since
 *       ServiceBulletinService keys on {@code dtcId}, not on the bean's
 *       transient I/O fields (status-bit, timestamp, snapshot).</li>
 *   <li><b>Read-only</b> — queries cached TSB articles via
 *       ServiceBulletinService. No VCM3 bus I/O. Olympus tool-authority
 *       tier: {@code "auto"}.</li>
 * </ul>
 *
 * <p>Serialization: Ford's return shape is {@code Map<DTC, List<Article>>}
 * where the map key is a complex bean. JSON objects need string keys,
 * so the adapter flattens to an array-of-entries before emitting:
 * {@code [{ "dtc": "P0300", "articles": [Article, …] }, …]}. This
 * keeps downstream Opus rendering simple + avoids walkBean dumping
 * bean-toString on the map key.
 *
 * <p>PR#H · extends the bundle's reach with bulletin lookup; matches
 * Session I's server-side tool coverage for feature parity.
 */
public final class GetServiceBulletinsForDTCsAdapter implements CommandAdapter {
    @Override public String publicName() { return "getServiceBulletinsForDTCs"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetServiceBulletinsForDTCs"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(
            "dtcs",
            "string[] (required) — list of DTC codes to look up TSBs for (e.g. [\"P0300\", \"P0420\"]). Must be non-empty. Codes pass through verbatim to Ford's ServiceBulletinService; no normalization."
        );
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        List<String> codes = coerceStringList(args.get("dtcs"));
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("arg \"dtcs\" required and must be a non-empty string array");
        }
        List<DTC> dtcs = new ArrayList<>(codes.size());
        for (String code : codes) {
            DTC dtc = new DTC();
            dtc.setDTCId(code);
            dtcs.add(dtc);
        }
        return new GetServiceBulletinsForDTCs(dtcs);
    }

    /**
     * Flatten Ford's {@code Map<DTC, List<Article>>} into a JSON-friendly
     * array of {@code { dtc: String, articles: [...] }} entries.
     * JSON maps can't carry complex bean keys; an entries-array keeps
     * the DTC-code association explicit + walkBean handles the Article
     * list without toString drift.
     */
    @Override
    public Object serializeResult(Object result) {
        if (!(result instanceof Map)) {
            return Json.walkBean(result, 6);
        }
        Map<?, ?> raw = (Map<?, ?>) result;
        List<Map<String, Object>> entries = new ArrayList<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            Object key = e.getKey();
            String dtcCode = "";
            if (key instanceof DTC) {
                DTC d = (DTC) key;
                dtcCode = d.getDTCId() != null ? d.getDTCId() : "";
            } else if (key != null) {
                dtcCode = key.toString();
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dtc", dtcCode);
            entry.put("articles", Json.walkBean(e.getValue(), 6));
            entries.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries);
        return out;
    }

    /**
     * Coerce a JSON-decoded arg value into a {@code List<String>}.
     * Accepts actual arrays, comma-separated strings, or a single string
     * ({@code "P0300"} → {@code ["P0300"]}) for tech-ergonomics.
     */
    static List<String> coerceStringList(Object raw) {
        if (raw == null) return java.util.Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection) {
            for (Object v : (Collection<?>) raw) {
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (!s.isEmpty()) out.add(s);
                } else if (v != null) {
                    String s = v.toString().trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
            return out;
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return out;
            for (String part : s.split("[,\\s]+")) {
                String p = part.trim();
                if (!p.isEmpty()) out.add(p);
            }
            return out;
        }
        throw new IllegalArgumentException(
            "arg \"dtcs\" must be a string array or comma-separated string; got "
                + raw.getClass().getName()
        );
    }

}
