package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Serialization-agnostic view of a map entry. The bridge always returns a
 * presentable form (JSON tree, Compact/Portable schema, or class+size+preview)
 * — never raw Java-serialized bytes to the UI.
 */
@Value
@Builder
@Jacksonized
public class MapEntryView {
    String  keyJson;          // JSON or string repr of key
    String  valueJson;        // JSON tree if reflectable, else null
    String  valueClassName;   // FQN of value type
    Long    valueSizeBytes;
    String  valuePreviewBase64;  // first N bytes when not JSON-able
    Long    creationTimeMs;
    Long    lastAccessTimeMs;
    Long    ttlMs;
    Long    version;
}
