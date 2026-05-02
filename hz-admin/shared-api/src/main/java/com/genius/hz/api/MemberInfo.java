package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

@Value
@Builder
@Jacksonized
public class MemberInfo {
    String  uuid;
    /** Composed host:port — kept for backward compatibility with existing UI code. */
    String  address;
    /** IP or host string as Hazelcast knows it (e.g. "10.0.0.5" or "node-3"). */
    String  host;
    int     port;
    /**
     * Reverse-DNS lookup of {@link #host}. Empty string when the lookup failed, timed out,
     * or returned the IP back unchanged. UI should fall back to {@link #host} in that case.
     * The bridge caches the result per-IP so we don't hit DNS on every member-list refresh.
     */
    String  hostName;
    boolean lite;
    String  version;
    boolean local;
    Map<String, String> attributes;
}
