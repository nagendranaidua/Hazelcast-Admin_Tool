package com.genius.hz.bridge.v5;

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
 * SPI factory for Hazelcast 5.1.x / 5.2.x clusters.
 * Discovered via {@code META-INF/services/com.genius.hz.bridge.HazelcastBridgeFactory}.
 */
public class HazelcastBridge5xFactory implements HazelcastBridgeFactory {

    @Override
    public String supportedMajorVersion() { return "5"; }

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

            // never let the admin app's misconfig stall a UI thread
            cfg.setProperty("hazelcast.client.invocation.timeout.seconds",
                    String.valueOf(Math.max(1, c.getInvocationTimeoutMs() / 1000)));
            cfg.setProperty("hazelcast.client.heartbeat.timeout", "10000");

            HazelcastInstance inst = HazelcastClient.newHazelcastClient(cfg);
            return new HazelcastBridge5x(inst);
        } catch (Exception e) {
            throw new BridgeConnectException(
                    "Failed to connect to Hazelcast 5.x cluster '" + c.getClusterName()
                            + "' after " + Math.max(1, c.getConnectMaxAttempts()) + " attempt(s) "
                            + "(" + (long) Math.max(1, c.getConnectMaxAttempts()) * Math.max(0, c.getConnectBackoffMs())
                            + "ms total budget): " + rootCauseMessage(e), e);
        }
    }

    /**
     * Replace Hazelcast's default "retry the cluster forever" with a bounded budget.
     * <p>
     * Hazelcast's {@code ConnectionRetryConfig.clusterConnectTimeoutMillis} caps total
     * time the client spends trying to reach the cluster. We use a flat backoff
     * (multiplier=1.0) so the budget is exactly {@code attempts × backoff} —
     * an operator setting "3 attempts at 2s each" gets a 6s total wait, predictable.
     * <p>
     * Reconnect mode stays at {@code ON} so already-connected drops still trigger
     * a reconnect, bounded by the same retry config so the client doesn't hang
     * forever on a permanent network partition.
     */
    private static void applyBoundedRetry(ClientConfig cfg, BridgeConnectConfig c) {
        int attempts = Math.max(1, c.getConnectMaxAttempts());
        int backoff  = Math.max(0, c.getConnectBackoffMs());
        long totalBudgetMs = (long) attempts * backoff;

        ClientConnectionStrategyConfig strat = cfg.getConnectionStrategyConfig();
        strat.setReconnectMode(ClientConnectionStrategyConfig.ReconnectMode.ON);
        strat.setAsyncStart(false);   // block on initial connect so caller sees the failure

        ConnectionRetryConfig retry = strat.getConnectionRetryConfig();
        retry.setInitialBackoffMillis(Math.max(100, backoff));
        retry.setMaxBackoffMillis(Math.max(100, backoff));
        retry.setMultiplier(1.0);
        retry.setJitter(0.0);
        retry.setClusterConnectTimeoutMillis(Math.max(100L, totalBudgetMs));
    }

    /** Hazelcast wraps connect failures deeply; surface the leaf for the UI. */
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
        p.setProperty("protocol", c.getTlsProtocol() != null ? c.getTlsProtocol() : "TLSv1.3");
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
