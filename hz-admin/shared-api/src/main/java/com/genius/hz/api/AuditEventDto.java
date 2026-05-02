package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

@Value
@Builder
@Jacksonized
public class AuditEventDto {
    Long    id;
    Instant timestamp;
    String  actor;             // username
    String  actorRole;
    String  cluster;           // cluster name (nullable for app-level events)
    String  action;            // MAP_PUT, MEMBER_SHUTDOWN, USER_CREATE, ...
    String  target;            // e.g. mapName/key
    String  reason;            // mandatory for write ops
    String  requestHash;       // sha256 of request payload
    String  outcome;           // SUCCESS / DENIED / FAILED
    String  errorMessage;
}
