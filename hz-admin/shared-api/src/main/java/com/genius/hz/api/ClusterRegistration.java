package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * Payload to register a Hazelcast cluster with the admin app.
 * Security profile is per-cluster: plain / TLS / mTLS + creds.
 */
@Value
@Builder
@Jacksonized
public class ClusterRegistration {
    String   name;            // unique display name
    String   environment;     // DEV / QA / STAGE / PROD (free-form)
    String   clusterName;     // Hazelcast cluster name (group)
    String   majorVersion;    // "5.1" | "5.2"  (4.x deferred)
    List<String> memberAddresses;  // host:port list for client bootstrap
    SecurityProfile security;
}
