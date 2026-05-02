package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/** Uniform error envelope for all REST endpoints. */
@Value
@Builder
@Jacksonized
public class ApiError {
    Instant timestamp;
    int     status;
    String  code;          // stable machine code: AUTH_DENIED, CLUSTER_UNREACHABLE, ...
    String  message;       // human-readable
    String  path;
    String  traceId;
}
