package com.example.oraclebench.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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

    public record QueryResult(int id, long rows, double elapsedMs, String sqlId) {
    }

    public QueryResult run(int id, String param) throws SQLException {
        String template = registry.get(id);
        if (template == null) {
            throw new IllegalArgumentException("No predefined query with id=" + id);
        }

        boolean concatenated = template.contains(INJECT_TOKEN);
        boolean bound = template.contains("?");

        // The exact statement text that will be sent to the database.
        String sql = concatenated
                ? template.replace(INJECT_TOKEN, param == null ? "" : param)
                : template;

        if (concatenated) {
            log.warn("Executing query #{} (CONCATENATED, no bind): {}", id, sql);
        } else if (bound) {
            log.info("Executing query #{} with bind p='{}': {}", id, param, sql);
        } else {
            log.info("Executing query #{}: {}", id, sql);
        }

        try (Connection conn = dataSource.getConnection()) {
            long rows;
            double elapsedMs;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (bound && !concatenated) {
                    ps.setString(1, param);
                }

                // Time execution + full fetch, and nothing else.
                long startNs = System.nanoTime();
                long count = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                    }
                }
                elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
                rows = count;
            }

            String sqlId = lookupSqlId(conn, sql);
            log.info("Query #{} -> rows={}, elapsedMs={}, sqlId={}", id, rows, String.format("%.3f", elapsedMs), sqlId);
            return new QueryResult(id, rows, elapsedMs, sqlId);
        }
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
