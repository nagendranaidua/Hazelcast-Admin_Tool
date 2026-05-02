package com.genius.hz.admin.sql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Backend-side bookkeeping for streaming SQL cursors.
 *
 * <p>The bridges already hold the {@code SqlResult} and reap idle cursors on access; this
 * registry adds two things on top:
 * <ul>
 *   <li>A per-cursor {@link ReentrantLock} so the controller can serialize
 *       {@code fetchSqlPage} calls that arrive concurrently for the same cursor — the bridge
 *       iterators are not thread-safe.</li>
 *   <li>An owner-check map so cursors opened by user A can't be advanced or closed by user B,
 *       defending against UI-state mistakes more than against attackers.</li>
 * </ul>
 *
 * <p>Idle eviction here mirrors the bridge-side TTL (5 minutes); if the registry forgets a
 * cursor, the bridge will too on its next sweep, so they stay in sync without coordination.
 */
@Component
public class SqlCursorRegistry {

    private static final long IDLE_TTL_MIN = 5;

    private final Cache<String, Entry> entries = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_TTL_MIN, TimeUnit.MINUTES)
            .maximumSize(2_000)
            .build();

    public void register(String cursorId, long clusterId, String owner) {
        entries.put(cursorId, new Entry(clusterId, owner));
    }

    public Entry get(String cursorId) {
        return entries.getIfPresent(cursorId);
    }

    public void release(String cursorId) {
        entries.invalidate(cursorId);
    }

    public static final class Entry {
        public final long          clusterId;
        public final String        owner;
        public final ReentrantLock lock = new ReentrantLock();
        Entry(long clusterId, String owner) {
            this.clusterId = clusterId;
            this.owner     = owner;
        }
    }
}
