package com.genius.hz.admin.sse;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.bridge.HazelcastBridge;
import com.genius.hz.bridge.MembershipEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-cluster fan-out hub for membership events.
 *
 * <p>Lazily attaches a {@code MembershipListener} on the cluster's bridge the first time a
 * subscriber registers; tears it down when the last subscriber leaves. Each registered
 * {@link SseEmitter} receives JSON events with one of:
 * <ul>
 *   <li>{@code event: snapshot} — initial member list right after subscribe</li>
 *   <li>{@code event: member-added}</li>
 *   <li>{@code event: member-removed}</li>
 *   <li>{@code event: heartbeat} — every 30s, keeps proxies & browsers from hanging up</li>
 * </ul>
 */
@Component
public class SseHub {

    private static final Logger LOG = LoggerFactory.getLogger(SseHub.class);
    private static final long EMITTER_TIMEOUT_MS = 0L; // 0 == no timeout from Spring's side

    private final BridgeRouter router;
    private final Map<Long, ClusterChannel> channels = new ConcurrentHashMap<>();

    public SseHub(BridgeRouter router) { this.router = router; }

    /** Register a new subscriber for a cluster's membership events. */
    public SseEmitter subscribeMembership(long clusterId) {
        ClusterChannel ch = channels.computeIfAbsent(clusterId, ClusterChannel::new);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> ch.remove(emitter));
        emitter.onTimeout(()    -> ch.remove(emitter));
        emitter.onError(t       -> ch.remove(emitter));
        ch.add(emitter);
        // Send initial snapshot so the client doesn't have to make a separate REST call
        try {
            HazelcastBridge b = router.bridgeFor(clusterId);
            emitter.send(SseEmitter.event().name("snapshot").data(b.listMembers()));
        } catch (Exception e) {
            try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); }
            catch (IOException ignored) {}
        }
        return emitter;
    }

    /** 30-second heartbeat across all open emitters; also drops dead ones. */
    @Scheduled(fixedDelay = 30_000L)
    void heartbeat() {
        for (ClusterChannel ch : channels.values()) {
            ch.broadcast("heartbeat", System.currentTimeMillis());
        }
    }

    @PreDestroy
    void shutdown() {
        for (ClusterChannel ch : channels.values()) ch.closeAll();
        channels.clear();
    }

    // ---- per-cluster channel ---------------------------------------------------------
    private final class ClusterChannel {
        final long clusterId;
        final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
        volatile String subscriptionId;

        ClusterChannel(long id) { this.clusterId = id; }

        void add(SseEmitter e) {
            emitters.add(e);
            ensureBridgeListener();
        }

        void remove(SseEmitter e) {
            emitters.remove(e);
            if (emitters.isEmpty()) detachBridgeListener();
        }

        void broadcast(String name, Object data) {
            for (SseEmitter e : emitters) {
                try {
                    e.send(SseEmitter.event().name(name).data(data));
                } catch (Exception ex) {
                    // emitter is dead/closed — drop it; onError will also fire
                    emitters.remove(e);
                    try { e.complete(); } catch (Exception ignored) {}
                }
            }
            if (emitters.isEmpty()) detachBridgeListener();
        }

        synchronized void ensureBridgeListener() {
            if (subscriptionId != null) return;
            try {
                HazelcastBridge b = router.bridgeFor(clusterId);
                subscriptionId = b.subscribeMembershipEvents((MembershipEvent ev) -> {
                    String name = ev.getType() == MembershipEvent.Type.ADDED ? "member-added" : "member-removed";
                    broadcast(name, ev.getMember());
                });
            } catch (Exception e) {
                LOG.warn("SSE membership subscribe failed for cluster {}: {}", clusterId, e.toString());
            }
        }

        synchronized void detachBridgeListener() {
            if (subscriptionId == null) return;
            try { router.bridgeFor(clusterId).unsubscribe(subscriptionId); }
            catch (Exception ignored) {}
            subscriptionId = null;
        }

        void closeAll() {
            detachBridgeListener();
            for (SseEmitter e : emitters) {
                try { e.complete(); } catch (Exception ignored) {}
            }
            emitters.clear();
        }
    }
}
