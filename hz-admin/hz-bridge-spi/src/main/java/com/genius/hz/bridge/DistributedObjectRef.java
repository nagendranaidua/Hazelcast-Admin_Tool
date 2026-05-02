package com.genius.hz.bridge;

import lombok.Value;

@Value
public class DistributedObjectRef {
    String name;
    Type   type;

    public enum Type {
        IMAP, QUEUE, TOPIC, RELIABLE_TOPIC, MULTIMAP, REPLICATED_MAP,
        SET, LIST, RINGBUFFER, JCACHE, CP_ATOMIC_LONG, CP_ATOMIC_REF,
        CP_LOCK, CP_SEMAPHORE, CP_LATCH, OTHER
    }
}
