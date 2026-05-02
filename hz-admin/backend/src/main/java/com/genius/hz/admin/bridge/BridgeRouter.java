package com.genius.hz.admin.bridge;

import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.admin.service.CryptoService;
import com.genius.hz.bridge.BridgeConnectConfig;
import com.genius.hz.bridge.BridgeConnectException;
import com.genius.hz.bridge.HazelcastBridge;
import com.genius.hz.bridge.HazelcastBridgeFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Routes "operate on cluster X" calls to the right HazelcastBridge implementation.
 *
 * <p>Holds the per-cluster bridge cache (build on first use, evict after configured idle).
 * Delegates implementation lookup to {@link BridgeRegistry} which manages the isolated
 * URLClassLoaders for each Hazelcast major version. Resilience4j circuit breakers wrap
 * the *callers* of bridge methods (annotated services), not this class.
 */
@Component
public class BridgeRouter {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeRouter.class);

    private final BridgeRegistry         registry;
    private final ClusterRepository      clusters;
    private final CryptoService          crypto;
    private final int                    idleEvictMin;
    private final int                    connectMs;
    private final int                    invocationMs;
    private final int                    connectMaxAttempts;
    private final int                    connectBackoffMs;

    private Cache<Long, HazelcastBridge> bridges;

    public BridgeRouter(BridgeRegistry registry,
                        ClusterRepository clusters,
                        CryptoService crypto,
                        @Value("${hz-admin.bridge.idle-eviction-min}") int idleEvictMin,
                        @Value("${hz-admin.bridge.connect-timeout-ms}") int connectMs,
                        @Value("${hz-admin.bridge.invocation-timeout-ms}") int invocationMs,
                        @Value("${hz-admin.bridge.connect-max-attempts:3}") int connectMaxAttempts,
                        @Value("${hz-admin.bridge.connect-backoff-ms:2000}") int connectBackoffMs) {
        this.registry           = registry;
        this.clusters           = clusters;
        this.crypto             = crypto;
        this.idleEvictMin       = idleEvictMin;
        this.connectMs          = connectMs;
        this.invocationMs       = invocationMs;
        this.connectMaxAttempts = Math.max(1, connectMaxAttempts);
        this.connectBackoffMs   = Math.max(0, connectBackoffMs);
    }

    @PostConstruct
    void init() {
        this.bridges = Caffeine.newBuilder()
                .expireAfterAccess(idleEvictMin, TimeUnit.MINUTES)
                .removalListener((Long id, HazelcastBridge b, RemovalCause cause) -> {
                    if (b != null) {
                        LOG.info("Closing bridge for cluster id={} (reason={})", id, cause);
                        try { b.close(); } catch (Exception e) { LOG.warn("close failed: {}", e.toString()); }
                    }
                })
                .build();
        LOG.info("BridgeRouter ready. Available HZ majors via registry: {}",
                 registry.supportedMajors());
    }

    @PreDestroy
    void shutdown() {
        if (bridges != null) bridges.invalidateAll();
    }

    /** Get-or-open the bridge for a cluster id. Throws if cluster missing/disabled. */
    public HazelcastBridge bridgeFor(long clusterId) {
        HazelcastBridge b = bridges.getIfPresent(clusterId);
        if (b != null && b.isConnected()) return b;
        if (b != null) bridges.invalidate(clusterId); // stale

        Cluster c = clusters.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cluster id: " + clusterId));
        if (!c.isEnabled())
            throw new IllegalStateException("Cluster '" + c.getName() + "' is disabled");

        String major = c.getMajorVersion().split("\\.")[0]; // "4" or "5"
        HazelcastBridgeFactory f = registry.factoryFor(major);

        BridgeConnectConfig cfg = BridgeConnectConfig.builder()
                .clusterName(c.getHzClusterName())
                .memberAddresses(Arrays.asList(c.getMemberAddresses().split("\\s*,\\s*")))
                .majorVersion(c.getMajorVersion())
                .username(c.getUsername())
                .password(crypto.decrypt(c.getPasswordCipher()))
                .securityMode(BridgeConnectConfig.SecurityMode.valueOf(c.getSecurityMode()))
                .truststorePath(c.getTruststorePath())
                .truststorePassword(crypto.decrypt(c.getTruststorePwdCipher()))
                .keystorePath(c.getKeystorePath())
                .keystorePassword(crypto.decrypt(c.getKeystorePwdCipher()))
                .tlsProtocol(c.getTlsProtocol())
                .connectTimeoutMs(connectMs)
                .invocationTimeoutMs(invocationMs)
                .connectMaxAttempts(connectMaxAttempts)
                .connectBackoffMs(connectBackoffMs)
                .build();
        try {
            HazelcastBridge fresh = f.open(cfg);
            bridges.put(clusterId, fresh);
            LOG.info("Opened bridge to cluster '{}' (id={}, hz {})",
                    c.getName(), clusterId, c.getMajorVersion());
            return fresh;
        } catch (BridgeConnectException e) {
            throw new IllegalStateException(
                "Failed to connect to cluster '" + c.getName() + "': " + e.getMessage(), e);
        }
    }

    public void evict(long clusterId) { bridges.invalidate(clusterId); }

    public Set<String> supportedMajorVersions() { return registry.supportedMajors(); }
}
