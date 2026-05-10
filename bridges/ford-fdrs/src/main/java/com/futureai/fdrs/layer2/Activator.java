package com.futureai.fdrs.layer2;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {
    /**
     * Read at runtime from the bundle's own META-INF/MANIFEST.MF
     * (Bundle-Version header). Initialized lazily in start() once the
     * BundleContext is available so we never drift between MANIFEST and
     * a hand-edited Java constant. Prior to 2026-05-09 this was a
     * hardcoded "0.2.5" string that silently lied to /health responders
     * after MANIFEST was bumped to 0.2.7 in #2534.
     */
    public static String BUNDLE_VERSION = "unknown";
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2");

    private Bridge bridge;

    @Override
    public void start(BundleContext ctx) throws Exception {
        BUNDLE_VERSION = ctx.getBundle().getVersion().toString();
        LOG.info("futureai-fdrs-layer2-bridge v" + BUNDLE_VERSION
            + " starting; bundleId=" + ctx.getBundle().getBundleId());
        bridge = new Bridge(ctx);
        try {
            bridge.start();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "bridge startup failed; bundle will stop", t);
            try { bridge.stop(); } catch (Throwable ignore) {}
            bridge = null;
            throw t instanceof Exception ? (Exception) t : new Exception(t);
        }
    }

    @Override
    public void stop(BundleContext ctx) {
        LOG.info("futureai-fdrs-layer2-bridge v" + BUNDLE_VERSION + " stopping");
        if (bridge != null) {
            try { bridge.stop(); } catch (Throwable t) { LOG.log(Level.WARNING, "bridge stop", t); }
            bridge = null;
        }
    }
}
