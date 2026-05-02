package com.genius.hz.bridge;

/**
 * Each bridge module exposes one factory via {@link java.util.ServiceLoader}.
 * The {@code BridgeRouter} discovers all factories at startup and picks one
 * by {@link #supportedMajorVersion()} matching the registered cluster's version.
 */
public interface HazelcastBridgeFactory {

    /** "5" for the 5.x bridge, "4" for the future 4.x bridge. */
    String supportedMajorVersion();

    /** Build a connected bridge. Must throw on connect failure. */
    HazelcastBridge open(BridgeConnectConfig config) throws BridgeConnectException;
}
