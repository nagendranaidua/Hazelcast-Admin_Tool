package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class QueryRequest {
    String mapName;
    String query;            // SQL on 5.x; Predicates.sql() on future 4.x
    int    limit;            // default 200, hard-cap configurable
    Long   cursor;           // OFFSET on 5.x; null on first page
    Long   timeoutMs;        // optional override; server enforces hard cap
}
