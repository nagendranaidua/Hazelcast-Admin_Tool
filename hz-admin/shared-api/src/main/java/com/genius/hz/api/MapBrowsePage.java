package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * One page of an IMap browse. {@code pageIndex} is 0-based and matches the request that produced
 * this page. {@code hasMore} is true iff a subsequent {@code pageIndex+1} request would yield rows.
 *
 * <p>{@code totalSize} is a best-effort snapshot at the time of the page request — for large maps
 * it may have drifted by the time the page lands, which is fine for UI display.
 */
@Value
@Builder
@Jacksonized
public class MapBrowsePage {
    String              mapName;
    int                 pageIndex;
    int                 pageSize;
    long                totalSize;
    boolean             hasMore;
    List<MapEntryView>  entries;
}
