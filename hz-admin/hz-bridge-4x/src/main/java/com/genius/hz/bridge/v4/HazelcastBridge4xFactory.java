package com.genius.hz.bridge.v4;

import com.genius.hz.bridge.BridgeConnectConfig;
import com.genius.hz.bridge.BridgeConnectException;
import com.genius.hz.bridge.HazelcastBridge;
import com.genius.hz.bridge.HazelcastBridgeFactory;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.client.config.ConnectionRetryConfig;
import com.hazelcast.config.SSLConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.security.UsernamePasswordCredentials;

import java.util.Properties;

/**
 * SPI factory for Hazelcast 4.x clusters. Targets the final 4.x release (4.2.8) and is
 * binary-compatible with cluster members from 4.0+ (Hazelcast preserves wire-protocol
 * compatibility within a major line).
 *
 * <p>Discovered via {@code META-INF/services/com.genius.hz.bridge.HazelcastBridgeFactory}.
 *
 * <p>Loaded in an isolated {@code URLClassLoader} by the backend's {@code BridgeRegistry}
 * so its {@code com.hazelcast.*} classes do not collide with the 5.x bridge's same-named
 * but ABI-incompatible classes.
 */
public class HazelcastBridge4xFactory implements HazelcastBridgeFactory {

    @Override
    public String supportedMajorVersion() { return "4"; }

    @Override
    public HazelcastBridge open(BridgeConnectConfig c) throws BridgeConnectException {
        try {
            ClientConfig cfg = new ClientConfig();
            cfg.setClusterName(c.getClusterName());
            cfg.setInstanceName("hz-admin-client-" + c.getClusterName() + "-" + System.nanoTime());

            ClientNetworkConfig net = cfg.getNetworkConfig();
            net.setAddresses(c.getMemberAddresses());
            net.setConnectionTimeout(c.getConnectTimeoutMs());
            net.setSmartRouting(true);
            net.setRedoOperation(false); // admin tool — fail fast, never silently retry mutations

            applySsl(net, c);
            applyAuth(cfg, c);
            applyBoundedRetry(cfg, c);

            // Same client-side guard rails as the 5.x bridge so a misconfigured cluster
            // doesn't stall a UI thread.
            cfg.setProperty("hazelcast.client.invocation.timeout.seconds",
                    String.valueOf(Math.max(1, c.getInvocationTimeoutMs() / 1000)));
            cfg.setProperty("hazelcast.client.heartbeat.timeout", "10000");

            HazelcastInstance inst = HazelcastClient.newHazelcastClient(cfg);
            return new HazelcastBridge4x(inst);
        } catch (Exception e) {
            throw new BridgeConnectException(
                    "Failed to connect to Hazelcast 4.x cluster '" + c.getClusterName()
                            + "' after " + Math.max(1, c.getConnectMaxAttempts()) + " attempt(s) "
                            + "(" + (long) Math.max(1, c.getConnectMaxAttempts()) * Math.max(0, c.getConnectBackoffMs())
                            + "ms total budget): " + rootCauseMessage(e), e);
        }
    }

    /**
     * Mirror of HazelcastBridge5xFactory.applyBoundedRetry — see that class for the rationale.
     * Hazelcast 4.2.x has the same {@code ClientConnectionStrategyConfig} +
     * {@code ConnectionRetryConfig} surface (added in 4.0), so the call sites are identical.
     */
    private static void applyBoundedRetry(ClientConfig cfg, BridgeConnectConfig c) {
        int attempts = Math.max(1, c.getConnectMaxAttempts());
        int backoff  = Math.max(0, c.getConnectBackoffMs());
        long totalBudgetMs = (long) attempts * backoff;

        ClientConnectionStrategyConfig strat = cfg.getConnectionStrategyConfig();
        strat.setReconnectMode(ClientConnectionStrategyConfig.ReconnectMode.ON);
        strat.setAsyncStart(false);

        ConnectionRetryConfig retry = strat.getConnectionRetryConfig();
        retry.setInitialBackoffMillis(Math.max(100, backoff));
        retry.setMaxBackoffMillis(Math.max(100, backoff));
        retry.setMultiplier(1.0);
        retry.setJitter(0.0);
        retry.setClusterConnectTimeoutMillis(Math.max(100L, totalBudgetMs));
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    private static void applySsl(ClientNetworkConfig net, BridgeConnectConfig c) {
        if (c.getSecurityMode() == BridgeConnectConfig.SecurityMode.PLAIN) return;
        SSLConfig ssl = new SSLConfig();
        ssl.setEnabled(true);
        Properties p = new Properties();
        // 4.x JVMs may not have TLS 1.3 enabled by default depending on JDK 8 patch level —
        // default to TLSv1.2 here unless explicitly overridden.
        p.setProperty("protocol", c.getTlsProtocol() != null ? c.getTlsProtocol() : "TLSv1.2");
        if (c.getTruststorePath() != null) {
            p.setProperty("trustStore", c.getTruststorePath());
            if (c.getTruststorePassword() != null)
                p.setProperty("trustStorePassword", c.getTruststorePassword());
        }
        if (c.getSecurityMode() == BridgeConnectConfig.SecurityMode.MTLS && c.getKeystorePath() != null) {
            p.setProperty("keyStore", c.getKeystorePath());
            if (c.getKeystorePassword() != null)
                p.setProperty("keyStorePassword", c.getKeystorePassword());
        }
        ssl.setProperties(p);
        net.setSSLConfig(ssl);
    }

    private static void applyAuth(ClientConfig cfg, BridgeConnectConfig c) {
        if (c.getUsername() != null && !c.getUsername().isEmpty() && c.getPassword() != null && !c.getPassword().isEmpty()) {
            cfg.getSecurityConfig().setCredentials(
                    new UsernamePasswordCredentials(c.getUsername(), c.getPassword()));
        }
    }
}
