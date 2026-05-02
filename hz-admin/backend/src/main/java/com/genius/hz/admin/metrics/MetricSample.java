package com.genius.hz.admin.metrics;

import lombok.Builder;
import lombok.Value;

/** Immutable point-in-time sample for one cluster. Series are columnar in the response. */
@Value
@Builder
public class MetricSample {
    long    ts;                     // epoch millis
    boolean connected;
    int     memberCount;
    int     partitionCount;
    boolean clusterSafe;
    long    bridgeCallLatencyMs;    // wall-clock for the listMembers() round-trip
    String  clusterState;           // ACTIVE / NO_MIGRATION / FROZEN / PASSIVE — null when !connected
}
