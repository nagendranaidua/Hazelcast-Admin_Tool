package com.genius.hz.bridge;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Decrypted (in-memory only) connection parameters handed to a bridge.
 * The backend's CryptoService decrypts ciphers from {@code SecurityProfile}
 * just before constructing this and never logs the plaintext fields.
 */
@Value
@Builder
public class BridgeConnectConfig {
    String       clusterName;
    List<String> memberAddresses;
    String       majorVersion;          // "5"
    String       username;              // nullable
    String       password;              // plain (in-memory only)
    SecurityMode securityMode;
    String       truststorePath;
    String       truststorePassword;
    String       keystorePath;
    String       keystorePassword;
    String       tlsProtocol;

    /** Per-socket TCP connect timeout (one address attempt). Unrelated to the overall retry budget. */
    int          connectTimeoutMs;

    /** Server-side invocation timeout for already-connected ops. */
    int          invocationTimeoutMs;

    /**
     * Maximum number of cluster-connect attempts before {@link HazelcastBridgeFactory#open}
     * throws {@link BridgeConnectException}. Replaces Hazelcast's default of "retry forever".
     * Total wait budget = {@code connectMaxAttempts × connectBackoffMs}.
     * Default (in BridgeRouter) is 3.
     */
    int          connectMaxAttempts;

    /**
     * Fixed backoff between cluster-connect attempts, in milliseconds. The bridges
     * deliberately use a flat backoff (not exponential) so the total budget is predictable
     * and visible to the operator. Default (in BridgeRouter) is 2000 ms.
     */
    int          connectBackoffMs;

    public enum SecurityMode { PLAIN, TLS, MTLS }
}
