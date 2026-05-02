package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * One page of a streaming or LIMIT/OFFSET SQL response.
 *
 * <p>{@code cursorId} is non-null only for streaming mode; the frontend POSTs it back to fetch
 * the next page. When the underlying {@code SqlResult} iterator is exhausted, the bridge
 * sets {@code done=true} and closes its handle automatically — but a polite client should still
 * call {@code closeSqlCursor} on unmount in case the user navigated away mid-stream.
 *
 * <p>For LIMIT/OFFSET mode, {@code cursorId} is null and {@code done} is true when fewer than
 * {@code pageSize} rows came back.
 *
 * <p>Rows are kept as raw lists so the frontend can render any column type. Each cell is
 * already JSON-serializable (numbers, strings, ISO timestamps, or stringified for opaque types).
 */
@Value
@Builder
@Jacksonized
public class SqlPage {
    /** Streaming cursor id; {@code null} for LIMIT/OFFSET mode or once {@code done=true}. */
    String              cursorId;
    /** Mode the bridge ran in: "STREAM" or "LIMIT". */
    String              mode;
    /** Column metadata in column order. */
    List<SqlColumnMeta> columns;
    /** Each row as a list of cell values matching {@code columns} order. */
    List<List<Object>>  rows;
    /** True when the underlying iterator is exhausted (or LIMIT was satisfied). */
    boolean             done;
    /** Page number (0-based) for LIMIT mode; matches request. Always 0 for the first STREAM page. */
    int                 pageIndex;
    long                elapsedMs;
}
