package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class SqlColumnMeta {
    String name;
    /** Hazelcast SqlColumnType.name() — e.g. VARCHAR, INTEGER, TIMESTAMP_WITH_TIME_ZONE, OBJECT. */
    String type;
    boolean nullable;
}
