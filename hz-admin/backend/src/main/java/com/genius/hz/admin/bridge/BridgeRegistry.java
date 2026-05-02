package com.genius.hz.admin.bridge;

import com.genius.hz.bridge.HazelcastBridgeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers Hazelcast bridge implementations on disk and exposes them keyed by major version.
 *
 * <p><b>Why a custom registry instead of plain ServiceLoader?</b><br>
 * Hazelcast 4.x and 5.x clients ship overlapping {@code com.hazelcast.*} packages with
 * incompatible APIs. They cannot coexist on a single classpath. Each bridge module is
 * therefore packaged as a self-contained shaded jar (excluding the SPI), and this registry
 * loads each jar in its own {@link URLClassLoader} whose parent is the application classloader.
 * That gives each bridge a private copy of {@code com.hazelcast.*}, while the SPI types
 * ({@link HazelcastBridgeFactory} et al.) come from the shared parent CL so type identity
 * across the seam is preserved.
 *
 * <p>Bridge JARs are picked up from {@code hz-admin.bridge.dir} (default
 * {@code ${HZ_ADMIN_HOME:-./}/bridges}) at startup. To add a new major version (e.g. 6.x)
 * later, drop a new {@code hz-bridge-6x-*.jar} into that directory and restart — no code
 * change needed.
 */
@Component
public class BridgeRegistry implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeRegistry.class);

    private final File bridgeDir;

    private final Map<String, HazelcastBridgeFactory> byMajor = new HashMap<>();
    private final List<URLClassLoader> openLoaders = new ArrayList<>();

    public BridgeRegistry(@Value("${hz-admin.bridge.dir:./bridges}") String bridgeDir) {
        this.bridgeDir = new File(bridgeDir).getAbsoluteFile();
    }

    @PostConstruct
    void load() {
        if (!bridgeDir.isDirectory()) {
            LOG.warn("Bridge directory '{}' does not exist; no Hazelcast bridges will be available. " +
                     "Drop hz-bridge-Nx-*.jar files there and restart.", bridgeDir);
            return;
        }
        File[] jars = bridgeDir.listFiles((d, n) ->
                n.toLowerCase().startsWith("hz-bridge-") && n.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOG.warn("No hz-bridge-*.jar files found in '{}'", bridgeDir);
            return;
        }

        ClassLoader parent = BridgeRegistry.class.getClassLoader();
        for (File jar : jars) {
            try {
                URL[] urls = { jar.toURI().toURL() };
                URLClassLoader child = new URLClassLoader(urls, parent);
                openLoaders.add(child);

                int found = 0;
                for (HazelcastBridgeFactory f :
                        ServiceLoader.load(HazelcastBridgeFactory.class, child)) {
                    String major = f.supportedMajorVersion();
                    HazelcastBridgeFactory existing = byMajor.put(major, f);
                    if (existing != null) {
                        LOG.warn("Multiple bridges declare support for Hazelcast major {}; " +
                                 "the later one ({}) wins.", major, jar.getName());
                    } else {
                        LOG.info("Loaded bridge for Hazelcast {}.x from {}", major, jar.getName());
                    }
                    found++;
                }
                if (found == 0) {
                    LOG.warn("No HazelcastBridgeFactory ServiceLoader entry found in {}", jar.getName());
                }
            } catch (Exception e) {
                LOG.error("Failed to load bridge jar '{}': {}", jar.getName(), e.toString(), e);
            }
        }
        LOG.info("BridgeRegistry initialised with majors={} from dir={}", byMajor.keySet(), bridgeDir);
    }

    /** Look up a factory by Hazelcast major version (e.g. "4", "5"). Never returns null without throwing. */
    public HazelcastBridgeFactory factoryFor(String majorVersion) {
        HazelcastBridgeFactory f = byMajor.get(majorVersion);
        if (f == null) {
            throw new IllegalStateException(
                "No Hazelcast bridge available for major version " + majorVersion +
                ". Loaded majors: " + byMajor.keySet() + ". " +
                "Make sure the corresponding hz-bridge-" + majorVersion + "x-*.jar is in '" + bridgeDir + "'.");
        }
        return f;
    }

    public Set<String> supportedMajors() { return Collections.unmodifiableSet(byMajor.keySet()); }

    public File bridgeDir() { return bridgeDir; }

    @PreDestroy
    @Override
    public void close() {
        byMajor.clear();
        for (URLClassLoader cl : openLoaders) {
            try { cl.close(); } catch (IOException e) { LOG.warn("URLClassLoader close failed: {}", e.toString()); }
        }
        openLoaders.clear();
    }
}
