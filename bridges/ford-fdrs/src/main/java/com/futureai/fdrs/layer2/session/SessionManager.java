package com.futureai.fdrs.layer2.session;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Bundle-local session registry with explicit concurrency and TTL eviction.
 *
 * <p>Concurrency model (per Session K's kickoff comment on PR #1569, carried
 * into the real-FDRS plan PR #1587 Amendment 5):
 *
 * <ul>
 *   <li><b>Per-session serialization.</b> Each session holds a
 *       {@link ReentrantLock}. {@link #acquire(String)} blocks if another
 *       thread is mid-invocation on the same session, so stateful Ford
 *       commands (SelectVehicle, PopulateMdxRefs, anything that mutates
 *       VehicleService's per-session cache) can't interleave within one
 *       session.</li>
 *   <li><b>Cross-session independence.</b> Sessions have separate locks;
 *       concurrent dispatches on different sessionIds proceed in parallel
 *       subject only to the {@link BundleHttpServer}'s 4-worker pool cap.</li>
 *   <li><b>TTL eviction.</b> Sessions idle for more than {@link #TTL_MS} are
 *       removed by a background reaper running every 5 min. Clients see a
 *       {@code kind:"session_expired"} 404 from the Router and re-bootstrap
 *       once per the v1.3 {@code Layer2ErrorKind} contract.</li>
 *   <li><b>Cap.</b> At most {@link #MAX_SESSIONS} concurrent sessions.
 *       Create beyond the cap throws {@link IllegalStateException}.</li>
 * </ul>
 */
public final class SessionManager {
    private static final Logger LOG = Logger.getLogger("com.futureai.fdrs.layer2.session");

    public static final int MAX_SESSIONS = 10;
    public static final long TTL_MS = 30L * 60L * 1000L;
    private static final long REAP_INTERVAL_MIN = 5L;

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public SessionManager() {
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "futureai-session-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(this::evictExpired,
            REAP_INTERVAL_MIN, REAP_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    /** Creates a new session and returns its opaque id. Throws if cap exceeded. */
    public String createSession() {
        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException(
                "session cap reached (" + MAX_SESSIONS + ") — close an existing session first");
        }
        String id = UUID.randomUUID().toString();
        sessions.put(id, new SessionState(id));
        LOG.info("session created: " + id);
        return id;
    }

    /**
     * Acquires the session's lock for the duration of a command invocation.
     * Blocks if another thread holds the lock. Returns {@code null} if no
     * session with that id exists (caller should 404 with {@code
     * kind:"session_expired"}). Caller must call {@link #release(SessionState)}
     * in a finally block.
     */
    public SessionState acquire(String id) {
        SessionState s = sessions.get(id);
        if (s == null) return null;
        s.lock.lock();
        s.lastAccess = System.currentTimeMillis();
        return s;
    }

    public void release(SessionState s) {
        if (s == null) return;
        s.lock.unlock();
    }

    /** Explicit teardown. Returns {@code true} if a session with that id existed. */
    public boolean remove(String id) {
        SessionState removed = sessions.remove(id);
        if (removed != null) {
            LOG.info("session removed: " + id);
            return true;
        }
        return false;
    }

    public Collection<String> ids() {
        return sessions.keySet();
    }

    public int size() {
        return sessions.size();
    }

    public void shutdown() {
        reaper.shutdownNow();
        sessions.clear();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            boolean expired = (now - e.getValue().lastAccess) > TTL_MS;
            if (expired) LOG.info("session expired (TTL): " + e.getKey());
            return expired;
        });
    }

    public static final class SessionState {
        public final String id;
        public final ReentrantLock lock = new ReentrantLock();
        public volatile long lastAccess = System.currentTimeMillis();
        public volatile String vin;

        SessionState(String id) { this.id = id; }
    }
}
