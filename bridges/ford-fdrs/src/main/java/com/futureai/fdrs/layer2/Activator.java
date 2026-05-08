package com.futureai.fdrs.layer2;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {
    public static final String BUNDLE_VERSION = "0.2.5";
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2");

    private Bridge bridge;

    @Override
    public void start(BundleContext ctx) throws Exception {
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
