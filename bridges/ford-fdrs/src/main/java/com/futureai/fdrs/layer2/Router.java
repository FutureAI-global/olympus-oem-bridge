package com.futureai.fdrs.layer2;

import com.ford.otx.command.Command;
import com.ford.otx.command.invoker.CommandInvoker;
import com.futureai.fdrs.layer2.session.BootstrapAdapter;
import com.futureai.fdrs.layer2.session.SessionManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Router {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.router");

    private final Bridge bridge;
    private final CommandAdapterRegistry registry;
    private final SessionManager sessions;
    private final BootstrapAdapter bootstrap;

    public Router(Bridge bridge, CommandAdapterRegistry registry, SessionManager sessions) {
        this.bridge = bridge;
        this.registry = registry;
        this.sessions = sessions;
        this.bootstrap = new BootstrapAdapter(sessions, bridge);
    }

    public HttpIo.Response dispatch(HttpIo.Request req) {
        try {
            if ("GET".equals(req.method) && "/health".equals(req.path)) return health();
            if ("GET".equals(req.method) && "/commands".equals(req.path)) return listCommands();
            if ("POST".equals(req.method) && "/session/bootstrap".equals(req.path)) {
                return handleBootstrap(req);
            }
            if ("DELETE".equals(req.method) && req.path.startsWith("/session/")) {
                String id = req.path.substring("/session/".length());
                return handleSessionDelete(id);
            }
            if ("POST".equals(req.method) && req.path.startsWith("/commands/")) {
                String name = req.path.substring("/commands/".length());
                int q = name.indexOf('?');
                if (q >= 0) name = name.substring(0, q);
                return invoke(name, req);
            }
            return errJson(404, "Not Found", "not_found", "path not recognized: " + req.method + " " + req.path);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "dispatch error", t);
            return errJson(500, "Internal Server Error", "internal_error", describe(t));
        }
    }

    private HttpIo.Response health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("bundleVersion", Activator.BUNDLE_VERSION);
        body.put("invokerAvailable", bridge.invoker() != null);
        body.put("commandsExposed", new ArrayList<>(registry.names()));
        body.put("activeSessions", sessions.size());
        return HttpIo.Response.json(200, "OK", Json.encode(body));
    }

    private HttpIo.Response listCommands() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (CommandAdapter a : registry.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("publicName", a.publicName());
            entry.put("commandClass", a.commandClass());
            entry.put("takesArgs", a.argShape());
            entries.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("commands", entries);
        return HttpIo.Response.json(200, "OK", Json.encode(body));
    }

    private HttpIo.Response handleBootstrap(HttpIo.Request req) {
        Map<String, Object> body = Collections.emptyMap();
        if (req.body != null && req.body.length > 0) {
            try {
                Object parsed = Json.parse(req.bodyAsString());
                if (parsed instanceof Map) {
                    body = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                        body.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            } catch (Throwable t) {
                return errJson(400, "Bad Request", "bad_request", "invalid JSON body: " + t.getMessage());
            }
        }
        try {
            Map<String, Object> result = bootstrap.bootstrap(body);
            return HttpIo.Response.json(200, "OK", Json.encode(result));
        } catch (IllegalArgumentException t) {
            return errJson(400, "Bad Request", "bad_args", describe(t));
        } catch (IllegalStateException t) {
            return errJson(503, "Service Unavailable", "invoker_unavailable", describe(t));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "bootstrap failed", t);
            return errJson(500, "Internal Server Error", "invocation_error", describe(t));
        }
    }

    private HttpIo.Response handleSessionDelete(String id) {
        boolean removed = sessions.remove(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sessionId", id);
        body.put("removed", removed);
        return HttpIo.Response.json(200, "OK", Json.encode(body));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private HttpIo.Response invoke(String publicName, HttpIo.Request req) {
        CommandAdapter adapter = registry.getByPublicName(publicName);
        if (adapter == null) {
            return errJson(403, "Forbidden", "allowlist_miss",
                "command not allowlisted: " + publicName
                    + " (exposed: " + registry.names() + ")");
        }
        CommandInvoker inv = bridge.invoker();
        if (inv == null) {
            return errJson(503, "Service Unavailable", "invoker_unavailable",
                "CommandInvoker service not yet bound in Felix registry");
        }

        Map<String, Object> args = Collections.emptyMap();
        String sessionId = null;
        if (req.body != null && req.body.length > 0) {
            try {
                Object parsed = Json.parse(req.bodyAsString());
                if (parsed instanceof Map) {
                    Object a = ((Map<?, ?>) parsed).get("args");
                    if (a instanceof Map) {
                        args = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) a).entrySet()) {
                            args.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                    Object sid = ((Map<?, ?>) parsed).get("sessionId");
                    if (sid instanceof String && !((String) sid).isEmpty()) {
                        sessionId = (String) sid;
                    }
                }
            } catch (Throwable t) {
                return errJson(400, "Bad Request", "bad_request", "invalid JSON body: " + t.getMessage());
            }
        }

        SessionManager.SessionState session = null;
        if (sessionId != null) {
            session = sessions.acquire(sessionId);
            if (session == null) {
                return errJson(404, "Not Found", "session_expired",
                    "session not found: " + sessionId);
            }
        }

        try {
            long t0 = System.currentTimeMillis();
            Command cmd;
            try {
                cmd = adapter.make(args);
            } catch (Throwable t) {
                return errJson(400, "Bad Request", "bad_args", describe(t));
            }

            Object result;
            try {
                result = inv.invoke(cmd);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "invoke " + publicName + " failed", t);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", false);
                body.put("error", describe(t));
                body.put("kind", "invocation_error");
                body.put("command", publicName);
                body.put("latencyMs", System.currentTimeMillis() - t0);
                return HttpIo.Response.json(500, "Internal Server Error", Json.encode(body));
            }

            Object serialized;
            try {
                serialized = adapter.serializeResult(result);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "serialize " + publicName + " failed", t);
                return errJson(500, "Internal Server Error", "serialize_error", describe(t));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("command", publicName);
            body.put("result", serialized);
            body.put("latencyMs", System.currentTimeMillis() - t0);
            if (sessionId != null) body.put("sessionId", sessionId);
            return HttpIo.Response.json(200, "OK", Json.encode(body));
        } finally {
            if (session != null) sessions.release(session);
        }
    }

    private static HttpIo.Response errJson(int status, String reason, String kind, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message);
        body.put("kind", kind);
        return HttpIo.Response.json(status, reason, Json.encode(body));
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
