package agent.persistence;

import java.util.List;

/**
 * Bounded result from a persisted history query.
 *
 * @param rows rows returned to the caller
 * @param limited true when more rows existed than the response limit allowed
 * @param limit maximum rows returned by this query
 */
public record HistoryQueryResult<T>(
    List<T> rows,
    boolean limited,
    int limit
) {
    public HistoryQueryResult {
        rows = List.copyOf(rows);
    }
}
