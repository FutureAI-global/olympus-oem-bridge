// MARK: 1 — replace this package with your OEM-specific path.
package com.example.oem.bridge.layer2;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal stdlib-only HTTP server for the bridge protocol. Listens on
 * 127.0.0.1 only — no network exposure. Stdlib HTTP avoids classpath
 * conflicts with whatever HTTP libs the OEM tool already pulls in
 * (Jetty, Tomcat, etc.).
 *
 * <p>Three endpoints (per {@code docs/BRIDGE_PROTOCOL.md}):
 *   - {@code GET  /health}              liveness + introspection
 *   - {@code GET  /commands}            allowlist enumeration
 *   - {@code POST /commands/<name>}     single-command dispatch
 *
 * <p>Localhost-only binding is enforced. DO NOT change to 0.0.0.0 — it
 * breaks the threat model (see {@code SECURITY.md}).
 */
public final class BundleHttpServer {
    private static final String LISTEN_HOST = "127.0.0.1";  // do not change
    private static final int LISTEN_PORT = 18082;

    private final Object commandService;
    private final List<CommandAdapter> allowlist;
    private HttpServer server = null;

    public BundleHttpServer(Object commandService) {
        this.commandService = commandService;
        this.allowlist = new ArrayList<>();
        registerAllowlist();
    }

    // MARK: 4 — register your CommandAdapter implementations here. Start
    // with one read-only command. Add more in follow-up PRs with a
    // safety justification per CONTRIBUTING.md.
    private void registerAllowlist() {
        // Example:
        // allowlist.add(new ListModulesAdapter());
        // allowlist.add(new ReadDIDAdapter());
        // allowlist.add(new ReadSelfTestDTCsAdapter());
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(LISTEN_HOST, LISTEN_PORT), 0);
        server.createContext("/health", exchange -> {
            sendJson(exchange, 200, healthBody());
        });
        server.createContext("/commands", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/commands")) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, errorBody("method_not_allowed", "GET only"));
                    return;
                }
                sendJson(exchange, 200, commandsBody());
                return;
            }
            // POST /commands/<name>
            if (!path.startsWith("/commands/")) {
                sendJson(exchange, 404, errorBody("not_found", "path: " + path));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorBody("method_not_allowed", "POST only"));
                return;
            }
            String name = path.substring("/commands/".length());
            handleInvoke(exchange, name);
        });
        server.setExecutor(null);  // use the default executor
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Body builders
    // ──────────────────────────────────────────────────────────────────

    private Map<String, Object> healthBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("bundleVersion", Activator.BUNDLE_VERSION);
        body.put("invokerAvailable", commandService != null);
        List<String> exposed = new ArrayList<>();
        for (CommandAdapter a : allowlist) exposed.add(a.publicName());
        body.put("commandsExposed", exposed);
        return body;
    }

    private Map<String, Object> commandsBody() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (CommandAdapter a : allowlist) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("publicName", a.publicName());
            entry.put("commandClass", a.commandClass());
            entry.put("takesArgs", a.argShape());
            entries.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("commands", entries);
        return body;
    }

    private void handleInvoke(com.sun.net.httpserver.HttpExchange exchange, String name) throws IOException {
        long t0 = System.currentTimeMillis();
        CommandAdapter adapter = findAdapter(name);
        if (adapter == null) {
            sendJson(exchange, 200, withTiming(errorBody("allowlist_miss", "command not in allowlist: " + name), name, t0));
            return;
        }
        Map<String, Object> args = parseArgs(exchange);
        Object result;
        try {
            result = adapter.invoke(commandService, args);
        } catch (Throwable t) {
            sendJson(exchange, 200, withTiming(errorBody("invocation_error", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage())), name, t0));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("command", name);
        body.put("result", result);
        body.put("latencyMs", System.currentTimeMillis() - t0);
        sendJson(exchange, 200, body);
    }

    private CommandAdapter findAdapter(String name) {
        for (CommandAdapter a : allowlist) {
            if (a.publicName().equals(name)) return a;
        }
        return null;
    }

    private Map<String, Object> parseArgs(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        // MARK: 5 — wire in your preferred JSON parser. The Ford-FDRS
        // reference uses a stdlib-only Json class to avoid classpath
        // conflicts. You may import org.json or javax.json if your OEM
        // tool already exposes one.
        return new HashMap<>();
    }

    private Map<String, Object> errorBody(String kind, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message);
        body.put("kind", kind);
        return body;
    }

    private Map<String, Object> withTiming(Map<String, Object> body, String name, long t0) {
        body.put("command", name);
        body.put("latencyMs", System.currentTimeMillis() - t0);
        return body;
    }

    // ──────────────────────────────────────────────────────────────────
    // JSON encoding (placeholder — see MARK: 5 above for parser hook)
    // ──────────────────────────────────────────────────────────────────

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        // Trivial map → JSON encoder; replace with a real parser/encoder.
        // The Ford-FDRS reference impl ships one in Json.java.
        StringBuilder sb = new StringBuilder();
        encodeMap(sb, body);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private void encodeMap(StringBuilder sb, Map<String, Object> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append('"').append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Boolean || v instanceof Number) sb.append(v);
            else if (v instanceof Map) encodeMap(sb, (Map<String, Object>) v);
            else if (v instanceof List) {
                sb.append('[');
                boolean innerFirst = true;
                for (Object item : (List<?>) v) {
                    if (!innerFirst) sb.append(',');
                    innerFirst = false;
                    if (item instanceof Map) encodeMap(sb, (Map<String, Object>) item);
                    else sb.append('"').append(String.valueOf(item).replace("\"", "\\\"")).append('"');
                }
                sb.append(']');
            } else {
                sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
            }
        }
        sb.append('}');
    }
}
