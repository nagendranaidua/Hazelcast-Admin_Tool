package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Builder
@Jacksonized
public class QueryResponse {
    List<MapEntryView> rows;
    Long    nextCursor;
    long    elapsedMs;
    long    totalScanned;        // rows examined by cluster (not result count)
    boolean truncated;           // true if hit hard cap
}
