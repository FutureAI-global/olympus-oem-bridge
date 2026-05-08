package com.futureai.fdrs.layer2;

import com.ford.otx.command.invoker.CommandInvoker;
import com.futureai.fdrs.layer2.session.SessionManager;
import java.io.IOException;
import java.util.logging.Logger;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

public class Bridge {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.bridge");
    public static final int DEFAULT_PORT = 18082;

    /**
     * PR#D · Static accessor so adapters that need to re-invoke Ford
     * commands outside the normal Router → adapter.make() path (e.g.
     * batch composition in {@code ReadDIDBatchAdapter}) can reach the
     * live {@link CommandInvoker}. Set in {@link #start()}, cleared in
     * {@link #stop()}. Null when the bundle is unloaded.
     */
    private static volatile Bridge ACTIVE;

    public static Bridge active() {
        return ACTIVE;
    }

    private final BundleContext ctx;
    private final CommandAdapterRegistry registry;
    private final SessionManager sessions;
    private final int port;
    private BundleHttpServer server;

    public Bridge(BundleContext ctx) {
        this.ctx = ctx;
        this.registry = new CommandAdapterRegistry();
        this.sessions = new SessionManager();
        String override = System.getProperty("futureai.fdrs.layer2.port");
        int p = DEFAULT_PORT;
        if (override != null) {
            try { p = Integer.parseInt(override); } catch (NumberFormatException ignore) {}
        }
        this.port = p;
    }

    public void start() throws IOException {
        Router router = new Router(this, registry, sessions);
        server = new BundleHttpServer(port, router);
        server.start();
        ACTIVE = this;
        LOG.info("bridge listening on 127.0.0.1:" + port + "; exposed commands: " + registry.names());
    }

    public void stop() {
        ACTIVE = null;
        if (server != null) {
            server.stop();
            server = null;
        }
        sessions.shutdown();
    }

    public CommandInvoker invoker() {
        ServiceReference<CommandInvoker> ref = ctx.getServiceReference(CommandInvoker.class);
        if (ref != null) {
            CommandInvoker svc = ctx.getService(ref);
            if (svc != null) return svc;
        }
        ServiceReference<?> byName = ctx.getServiceReference(
            "com.ford.otx.command.invoker.osgi.OSGICommandInvoker");
        if (byName != null) {
            Object svc = ctx.getService(byName);
            if (svc instanceof CommandInvoker) return (CommandInvoker) svc;
        }
        return null;
    }
}
