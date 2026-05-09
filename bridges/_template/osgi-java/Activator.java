// MARK: 1 — replace this package with your OEM-specific path.
package com.example.oem.bridge.layer2;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * OSGi Activator skeleton for an OEM diagnostic bridge.
 *
 * <p>Lifecycle:
 *   - The OEM tool's Felix/Equinox container loads this bundle on startup.
 *   - {@link #start} runs ONCE: discovers the OEM tool's command-dispatch
 *     service via {@code bundleContext.getServiceReference(...)}, then
 *     starts the HTTP server in a background thread.
 *   - {@link #stop} runs at OEM-tool shutdown: cleans up the HTTP server
 *     and any registered receivers.
 *
 * <p>Replace the four MARK: blocks below with your OEM-specific code.
 * See {@code bridges/ford-fdrs/src/main/java/com/futureai/fdrs/layer2/Activator.java}
 * for a working reference impl.
 *
 * <p>Per the Ford-FDRS bench (2026-05-09 Session N), watch for the
 * receiver-pattern gotcha documented in {@code docs/ADDING_AN_OEM.md} —
 * if your OEM uses receiver-based result delivery (very common in
 * OTX-derived stacks), a naive {@code invoker.invoke(cmd)} returns null
 * and the result is on the cmd object itself.
 */
public final class Activator implements BundleActivator {
    public static final String BUNDLE_VERSION = "0.0.1";

    // MARK: 2 — type your OEM tool's command-dispatch service interface.
    // For Ford FDRS this is com.ford.otx.command.invoker.CommandInvoker.
    // For BMW ISTA it would be ista.diag.CommandService.
    // For Mercedes XENTRY it would be xentry.diag.CommandBus.
    private Object commandService = null;
    private BundleHttpServer httpServer = null;

    @Override
    public void start(BundleContext context) throws Exception {
        // MARK: 3 — service-reference discovery for your OEM tool.
        // Replace the service name and cast to the right interface.
        ServiceReference<?> ref = context.getServiceReference("com.example.oem.CommandService");
        if (ref == null) {
            throw new IllegalStateException(
                "OEM CommandService not yet bound — bundle started too early?");
        }
        commandService = context.getService(ref);

        // Start the HTTP server. BundleHttpServer is generic (lives in
        // bridges/_template/osgi-java/BundleHttpServer.java) — no per-OEM
        // changes needed unless you want to add new HTTP routes.
        httpServer = new BundleHttpServer(commandService);
        httpServer.start();

        System.out.println("[bridge] " + BUNDLE_VERSION + " started");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        commandService = null;
        System.out.println("[bridge] stopped");
    }

    /** Test hook for command-service-aware adapters. */
    public Object commandService() { return commandService; }
}
