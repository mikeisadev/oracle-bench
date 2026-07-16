package com.example.oraclebench.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a predefined query and reports timing plus the Oracle SQL_ID.
 *
 * <p>All queries go through a {@link PreparedStatement}. Queries whose body
 * contains a {@code ?} get the request param bound to it. Query 99 is the
 * deliberate exception: its {@code __P__} token is string-concatenated into the
 * SQL, producing a distinct statement text (and therefore a hard parse) on every
 * distinct value — and a textbook SQL-injection hole.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    /** Marker replaced by concatenation (no bind) in query 99. */
    private static final String INJECT_TOKEN = "__P__";

    private final DataSource dataSource;
    private final QueryRegistry registry;

    public QueryService(DataSource dataSource, QueryRegistry registry) {
        this.dataSource = dataSource;
        this.registry = registry;
    }

    /** Hard cap on rows materialized into the response when data is requested. */
    private static final int MAX_DATA_ROWS = 500;

    /**
     * Outcome of a benchmark run. {@code columns}/{@code data} are populated only
     * when the caller asked to include the data; otherwise they are {@code null}
     * so the pure fetch loop stays representative for timing.
     */
    public record QueryResult(int id, long rows, double elapsedMs, String sqlId,
                              List<String> columns, List<Map<String, Object>> data,
                              boolean truncated) {
    }

    /**
     * A predefined query resolved to the exact statement text that will be sent to
     * the database, plus how the parameter is applied. Shared by the benchmark
     * runner and the tuning tools so they all execute the identical SQL.
     */
    public record BuiltStatement(int id, String sql, String bindParam,
                                 boolean bindable, boolean concatenated) {
    }

    /** Resolves query {@code id} into the SQL to run, applying {@code param}. */
    public BuiltStatement build(int id, String param) {
        String template = registry.get(id);
        if (template == null) {
            throw new IllegalArgumentException("No predefined query with id=" + id);
        }
        boolean concatenated = template.contains(INJECT_TOKEN);
        boolean bound = template.contains("?");
        String sql = concatenated
                ? template.replace(INJECT_TOKEN, param == null ? "" : param)
                : template;
        boolean bindable = bound && !concatenated;
        return new BuiltStatement(id, sql, bindable ? param : null, bindable, concatenated);
    }

    private void logExecution(BuiltStatement stmt) {
        if (stmt.concatenated()) {
            log.warn("Executing query #{} (CONCATENATED, no bind): {}", stmt.id(), stmt.sql());
        } else if (stmt.bindable()) {
            log.info("Executing query #{} with bind p='{}': {}", stmt.id(), stmt.bindParam(), stmt.sql());
        } else {
            log.info("Executing query #{}: {}", stmt.id(), stmt.sql());
        }
    }

    public QueryResult run(int id, String param, boolean includeData) throws SQLException {
        BuiltStatement stmt = build(id, param);
        String sql = stmt.sql();
        logExecution(stmt);

        try (Connection conn = dataSource.getConnection()) {
            long rows;
            double elapsedMs;
            List<String> columns = includeData ? new ArrayList<>() : null;
            List<Map<String, Object>> data = includeData ? new ArrayList<>() : null;
            boolean truncated = false;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (stmt.bindable()) {
                    ps.setString(1, stmt.bindParam());
                }

                // Time execution + full fetch, and nothing else.
                long startNs = System.nanoTime();
                long count = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    String[] colNames = includeData ? readColumnNames(rs, columns) : null;
                    while (rs.next()) {
                        count++;
                        // Always fetch every row (so timing reflects the full result),
                        // but only materialize up to MAX_DATA_ROWS into the response.
                        if (includeData) {
                            if (count <= MAX_DATA_ROWS) {
                                data.add(readRow(rs, colNames));
                            } else {
                                truncated = true;
                            }
                        }
                    }
                }
                elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
                rows = count;
            }

            String sqlId = lookupSqlId(conn, sql);
            log.info("Query #{} -> rows={}, elapsedMs={}, sqlId={}", id, rows, String.format("%.3f", elapsedMs), sqlId);
            return new QueryResult(id, rows, elapsedMs, sqlId, columns, data, truncated);
        }
    }

    private String[] readColumnNames(ResultSet rs, List<String> columns) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] names = new String[n];
        for (int i = 1; i <= n; i++) {
            names[i - 1] = md.getColumnLabel(i);
            columns.add(names[i - 1]);
        }
        return names;
    }

    private Map<String, Object> readRow(ResultSet rs, String[] colNames) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < colNames.length; i++) {
            Object value = rs.getObject(i + 1);
            // Render non-JSON-friendly types (TIMESTAMP, CLOB handles, ...) as strings.
            if (value != null && !(value instanceof Number) && !(value instanceof String)
                    && !(value instanceof Boolean)) {
                value = value.toString();
            }
            row.put(colNames[i], value);
        }
        return row;
    }

    /**
     * Finds the SQL_ID of the just-executed statement by matching its exact text
     * in V$SQL. Returns {@code null} when nothing matches, or when the connected
     * user lacks SELECT on V$SQL.
     */
    private String lookupSqlId(Connection conn, String sql) {
        final String lookup = "SELECT sql_id FROM v$sql WHERE sql_text = ?";
        log.info("Looking up SQL_ID: {} [bind='{}']", lookup, sql);
        try (PreparedStatement ps = conn.prepareStatement(lookup)) {
            ps.setString(1, sql);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.warn("Could not query V$SQL for SQL_ID (missing GRANT SELECT ON v_$sql to the app user?): {}", e.getMessage());
        }
        return null;
    }
}
