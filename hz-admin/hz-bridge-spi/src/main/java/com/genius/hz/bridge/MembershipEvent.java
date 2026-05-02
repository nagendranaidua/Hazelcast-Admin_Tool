package com.genius.hz.bridge;

import com.genius.hz.api.MemberInfo;
import lombok.Value;

import java.time.Instant;

@Value
public class MembershipEvent {
    Type       type;
    Instant    at;
    MemberInfo member;

    public enum Type { ADDED, REMOVED }
}
