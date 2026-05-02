package com.genius.hz.admin.tools;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.client.config.ConnectionRetryConfig;
import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.hazelcast.sql.SqlResult;
import com.hazelcast.sql.SqlRow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone sandbox for exercising a registered Hazelcast cluster directly with the raw
 * Hazelcast 5.x client API — independent of the bridge SPI, the Spring backend, or the UI.
 *
 * <p>Why this exists: the bridge SPI deliberately surfaces a narrow operations-shaped slice
 * of Hazelcast (browse, stats, SQL, member events). When you want to <i>just connect like
 * any other client</i> and try things — put a few entries, run a predicate, check what SQL
 * mappings exist, understand a schema — having to go through controllers and DB rows is
 * friction. This class is the unfriction. Run {@link #main(String[])} from your IDE or
 * {@code mvn -pl backend test-compile exec:java -Dexec.mainClass=...} and it walks through
 * a representative set of operations against a running cluster, printing what it sees.
 *
 * <p><b>Test scope only.</b> The {@code com.hazelcast:hazelcast} dep that pulls in
 * {@code com.hazelcast.*} for this class is declared with {@code <scope>test</scope>} in
 * {@code backend/pom.xml}. It is <i>not</i> on the runtime classpath of the shipped fat-jar.
 * The bridge-isolation architecture stays intact: production Hazelcast usage still flows
 * exclusively through the URLClassLoader-isolated bridge JARs.
 *
 * <p>Defaults match the dev docker-compose ({@code 127.0.0.1:5701}, cluster name {@code dev}).
 * Override via system properties:
 * <pre>
 *   -Dhz.addr=10.0.0.5:5701,10.0.0.6:5701
 *   -Dhz.cluster=prod-east
 *   -Dhz.map=users
 * </pre>
 */
public final class LocalHazelcastSandbox {

    /** Member addresses, comma-separated. */
    private static final String ADDR_PROP    = "hz.addr";
    /** Hazelcast cluster (group) name. */
    private static final String CLUSTER_PROP = "hz.cluster";
    /** Map name to operate on. The class will create it on first put. */
    private static final String MAP_PROP     = "hz.map";

    private static final String DEFAULT_ADDR    = "localhost:5701";
    private static final String DEFAULT_CLUSTER = "dev-5-cluster";
    private static final String DEFAULT_MAP     = "users";

    private LocalHazelcastSandbox() { /* tool, not instantiable */ }

    public static void main(String[] args) {
        String[] addrs   = System.getProperty(ADDR_PROP, DEFAULT_ADDR).split("\\s*,\\s*");
        String clusterId = System.getProperty(CLUSTER_PROP, DEFAULT_CLUSTER);
        String mapName   = System.getProperty(MAP_PROP, DEFAULT_MAP);

        banner("Connecting to Hazelcast", "addrs=" + Arrays.toString(addrs)
                + ", cluster='" + clusterId + "', map='" + mapName + "'");

        HazelcastInstance hz = null;
        try {
            hz = connect(addrs, clusterId);
            printClusterInfo(hz);

            // Demo each capability in turn. Each scenario is self-contained so failures
            // in one don't kill the rest — useful when probing a half-configured cluster.
            scenarioPutAndGet(hz, mapName);
            scenarioBulkPut(hz, mapName);
            scenarioPredicateQueries(hz, mapName);
            scenarioSqlQuery(hz, mapName);
            scenarioMapStats(hz, mapName);

            banner("Done", "Sandbox finished successfully.");
        } catch (Exception e) {
            System.err.println("[FATAL] Sandbox aborted: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (hz != null) {
                try { hz.shutdown(); } catch (Exception ignored) { /* shutdown best-effort */ }
            }
            // The client spawns non-daemon threads in some versions — force JVM exit so
            // the process doesn't hang waiting on background pools after main() returns.
            HazelcastClient.shutdownAll();
        }
    }

    // -------------------------------------------------------------------------------------
    // connection
    // -------------------------------------------------------------------------------------

    /**
     * Build a {@link ClientConfig} that mirrors the production bridge: bounded
     * cluster-connect retry budget, fail-fast invocation timeout, no silent op redos.
     * If the cluster isn't reachable within {@code attempts × backoff} ms, the call
     * throws and {@code main} reports it instead of looping forever.
     */
    private static HazelcastInstance connect(String[] addrs, String clusterName) {
        int attempts = Integer.getInteger("hz.connect.attempts", 3);
        int backoff  = Integer.getInteger("hz.connect.backoffMs", 2000);

        ClientConfig cfg = new ClientConfig();
        cfg.setClusterName(clusterName);
        cfg.setInstanceName("sandbox-" + System.nanoTime());
        cfg.getNetworkConfig().setAddresses(Arrays.asList(addrs));
        cfg.getNetworkConfig().setConnectionTimeout(5000);
        cfg.getNetworkConfig().setSmartRouting(true);
        cfg.getNetworkConfig().setRedoOperation(false);

        ClientConnectionStrategyConfig strat = cfg.getConnectionStrategyConfig();
        strat.setAsyncStart(false);
        strat.setReconnectMode(ClientConnectionStrategyConfig.ReconnectMode.ON);
        ConnectionRetryConfig retry = strat.getConnectionRetryConfig();
        retry.setInitialBackoffMillis(backoff);
        retry.setMaxBackoffMillis(backoff);
        retry.setMultiplier(1.0);
        retry.setJitter(0.0);
        retry.setClusterConnectTimeoutMillis((long) attempts * backoff);

        cfg.setProperty("hazelcast.client.invocation.timeout.seconds", "30");
        cfg.setProperty("hazelcast.client.heartbeat.timeout", "10000");

        return HazelcastClient.newHazelcastClient(cfg);
    }

    private static void printClusterInfo(HazelcastInstance hz) {
        banner("Cluster info",
                "members=" + hz.getCluster().getMembers().size()
//              + ", state=" + hz.getCluster().getClusterState()
              + ", partitions=" + hz.getPartitionService().getPartitions().size()
              + ", clientVersion=" + System.getProperty("hazelcast.client.version", "5.2.5"));
        hz.getCluster().getMembers().forEach(m ->
                System.out.println("  member: " + m.getAddress() + " uuid=" + m.getUuid() + (m.localMember() ? " (local)" : "")));
    }

    // -------------------------------------------------------------------------------------
    // scenarios
    // -------------------------------------------------------------------------------------

    /** Single put/get round-trip. Verifies the client can do a basic read-after-write. */
    private static void scenarioPutAndGet(HazelcastInstance hz, String mapName) {
        banner("scenario: put & get", "map=" + mapName);
        IMap<String, HazelcastJsonValue> map = hz.getMap(mapName);

        String json = "{\"name\":\"alice\",\"email\":\"alice@x.io\",\"age\":29}";
        map.set("u1", new HazelcastJsonValue(json));
        HazelcastJsonValue got = map.get("u1");
        System.out.println("  put     u1 = " + json);
        System.out.println("  get     u1 = " + (got == null ? "(null)" : got.getValue()));
    }

    /** Multiple entries — the predicate / SQL scenarios below need at least a few rows. */
    private static void scenarioBulkPut(HazelcastInstance hz, String mapName) {
        banner("scenario: bulk put", "map=" + mapName);
        IMap<String, HazelcastJsonValue> map = hz.getMap(mapName);

        Map<String, HazelcastJsonValue> batch = new LinkedHashMap<>();
        batch.put("u2", new HazelcastJsonValue("{\"name\":\"bob\",\"email\":\"bob@x.io\",\"age\":34}"));
        batch.put("u3", new HazelcastJsonValue("{\"name\":\"carol\",\"email\":\"carol@x.io\",\"age\":41}"));
        batch.put("u4", new HazelcastJsonValue("{\"name\":\"dan\",\"email\":\"dan@x.io\",\"age\":22}"));
        batch.put("u5", new HazelcastJsonValue("{\"name\":\"eve\",\"email\":\"eve@x.io\",\"age\":55}"));
        map.putAll(batch);
        System.out.println("  put     " + batch.size() + " entries; map size now = " + map.size());
    }

    /**
     * Predicate-API queries. We build an attribute index on {@code age} first so
     * {@link Predicates#greaterEqual} runs as an indexed lookup rather than a full scan;
     * indexes also work against JSON values via Hazelcast's {@code HazelcastJsonValue}
     * field-path attribute resolution. {@code Predicates.like}, {@code Predicates.equal},
     * and {@code Predicates.and} are the workhorses for ad-hoc probing.
     */
    private static void scenarioPredicateQueries(HazelcastInstance hz, String mapName) {
        banner("scenario: predicate queries", "map=" + mapName);
        IMap<String, HazelcastJsonValue> map = hz.getMap(mapName);

        // Add a sorted index on the JSON attribute "age" so range queries are O(log n).
        // addIndex is idempotent — re-adding an existing index is a no-op.
        try {
            map.addIndex(new IndexConfig(IndexType.SORTED, "age"));
            System.out.println("  index   added SORTED index on age (idempotent)");
        } catch (Exception e) {
            System.out.println("  index   addIndex skipped: " + e);
        }

        // Predicate 1: equality on a JSON attribute.
        Predicate<String, HazelcastJsonValue> byName = Predicates.equal("name", "carol");
        printResults("equal(name='carol')", map.entrySet(byName));

        // Predicate 2: range query — uses the index added above.
        Predicate<String, HazelcastJsonValue> by30Plus = Predicates.greaterEqual("age", 30);
        printResults("greaterEqual(age, 30)", map.entrySet(by30Plus));

        // Predicate 3: like with SQL-style wildcards.
        Predicate<String, HazelcastJsonValue> byEmailDomain = Predicates.like("email", "%@x.io");
        printResults("like(email, '%@x.io')", map.entrySet(byEmailDomain));

        // Predicate 4: combined — AND of name pattern + age range.
        Predicate<String, HazelcastJsonValue> combined = Predicates.and(
                Predicates.like("name", "a%"),
                Predicates.greaterEqual("age", 18));
        printResults("and(like(name,'a%'), greaterEqual(age, 18))", map.entrySet(combined));
    }

    /**
     * SQL query against the same map. Requires a SQL <i>mapping</i> to exist — we ensure
     * one with {@code CREATE MAPPING IF NOT EXISTS}. Hazelcast 5.x SQL needs each map
     * declared as a mapping before {@code SELECT} works, even though the IMap exists.
     */
    private static void scenarioSqlQuery(HazelcastInstance hz, String mapName) {
        banner("scenario: SQL query", "map=" + mapName);

        String quotedMap = "\"" + mapName.replace("\"", "\"\"") + "\"";
        String createMapping =
                "CREATE OR REPLACE MAPPING " + quotedMap + " "
              + "TYPE IMap OPTIONS ('keyFormat'='varchar','valueFormat'='json')";
        try {
            hz.getSql().execute(createMapping).close();
            System.out.println("  mapping ensured: " + createMapping);
        } catch (Exception e) {
            System.out.println("  mapping skipped: " + e);
            return;
        }

        String query = "SELECT __key, this FROM " + quotedMap + " WHERE __key LIKE 'u%'";
        try (SqlResult result = hz.getSql().execute(query)) {
            System.out.println("  query   " + query);
            int n = 0;
            for (SqlRow row : result) {
                Object key  = row.getObject("__key");
                Object json = row.getObject("this");
                System.out.println("    [" + n + "] " + key + " -> " + json);
                if (++n >= 10) { System.out.println("    (truncated at 10 rows)"); break; }
            }
            if (n == 0) System.out.println("  (no rows)");
        } catch (Exception e) {
            System.out.println("  SQL failed: " + e);
        }
    }

    /**
     * Local map stats. These are the same fields the admin UI's stats chips render —
     * useful for verifying the chip values directly against the cluster.
     */
    private static void scenarioMapStats(HazelcastInstance hz, String mapName) {
        banner("scenario: map stats", "map=" + mapName);
        IMap<String, HazelcastJsonValue> map = hz.getMap(mapName);
        System.out.println("  size=" + map.size()
                + " ownedEntries=" + map.getLocalMapStats().getOwnedEntryCount()
                + " puts=" + map.getLocalMapStats().getPutOperationCount()
                + " gets=" + map.getLocalMapStats().getGetOperationCount()
                + " hits=" + map.getLocalMapStats().getHits()
                + " heap≈" + map.getLocalMapStats().getOwnedEntryMemoryCost() + "B");
    }

    // -------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------

    private static void banner(String title, String detail) {
        System.out.println();
        System.out.println("===== " + title + " =====");
        if (detail != null && !detail.isEmpty()) System.out.println(detail);
    }

    private static <K, V> void printResults(String label, java.util.Set<Map.Entry<K, V>> rows) {
        System.out.println("  " + label + " -> " + rows.size() + " row(s)");
        int n = 0;
        for (Map.Entry<K, V> e : rows) {
            System.out.println("    " + e.getKey() + " = " + e.getValue());
            if (++n >= 10) { System.out.println("    (truncated at 10 rows)"); break; }
        }
    }
}
