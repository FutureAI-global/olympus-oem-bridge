package com.futureai.fdrs.layer2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BundleHttpServer {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.http");

    private final int port;
    private final Router router;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread accepter;
    private volatile boolean running;

    public BundleHttpServer(int port, Router router) {
        this.port = port;
        this.router = router;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "futureai-bridge-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;
        accepter = new Thread(this::acceptLoop, "futureai-bridge-accept");
        accepter.setDaemon(true);
        accepter.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignore) {}
        if (pool != null) {
            pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        }
        LOG.info("http server stopped");
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = serverSocket.accept();
            } catch (SocketException se) {
                if (running) LOG.log(Level.WARNING, "accept error", se);
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "accept loop error", e);
                continue;
            }
            final Socket socket = s;
            pool.submit(() -> handle(socket));
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(10_000);
            HttpIo.Request req = HttpIo.readRequest(in);
            HttpIo.Response resp = router.dispatch(req);
            HttpIo.writeResponse(out, resp);
        } catch (IOException e) {
            LOG.log(Level.FINE, "request i/o error", e);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "unhandled request error", t);
        }
    }
}
