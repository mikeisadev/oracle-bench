package com.example.oraclebench.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read-only exploration of the connected user's schema via the Oracle data
 * dictionary ({@code USER_TABLES}, {@code USER_TAB_COLUMNS}, {@code USER_INDEXES},
 * {@code USER_SEGMENTS}). Everything here is meant to support the tuning workshop:
 * row estimates come from optimizer statistics, segment sizes from the storage
 * layer, so students can see the gap between "what the optimizer believes" and
 * "what is actually stored".
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    /** Valid unquoted Oracle identifier — used to whitelist names before COUNT(*). */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Z0-9_$#]+$");

    private final JdbcTemplate jdbc;

    public SchemaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tables owned by the connected user, with stats-based row counts and segment size. */
    public List<Map<String, Object>> listTables() {
        String sql = """
                SELECT t.table_name,
                       t.num_rows,
                       t.last_analyzed,
                       NVL(s.bytes, 0) AS bytes
                FROM user_tables t
                LEFT JOIN user_segments s
                  ON s.segment_name = t.table_name
                 AND s.segment_type = 'TABLE'
                ORDER BY t.table_name
                """;
        log.info("Schema: listing tables");
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long bytes = rs.getLong("bytes");
            row.put("tableName", rs.getString("table_name"));
            row.put("numRows", rs.getObject("num_rows")); // null when never analyzed
            row.put("bytes", bytes);
            row.put("sizeMb", round2(bytes / 1024.0 / 1024.0));
            row.put("lastAnalyzed", asString(rs.getObject("last_analyzed")));
            return row;
        });
    }

    /** Full detail for one table: stats, columns, indexes, primary key. */
    public Map<String, Object> tableDetail(String rawName) {
        String name = normalize(rawName);
        log.info("Schema: detail for table {}", name);

        Map<String, Object> stats = jdbc.query("""
                SELECT t.num_rows, t.blocks, t.avg_row_len, t.last_analyzed,
                       NVL(s.bytes, 0) AS bytes
                FROM user_tables t
                LEFT JOIN user_segments s
                  ON s.segment_name = t.table_name
                 AND s.segment_type = 'TABLE'
                WHERE t.table_name = ?
                """, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                long bytes = rs.getLong("bytes");
                m.put("numRows", rs.getObject("num_rows"));
                m.put("blocks", rs.getObject("blocks"));
                m.put("avgRowLen", rs.getObject("avg_row_len"));
                m.put("bytes", bytes);
                m.put("sizeMb", round2(bytes / 1024.0 / 1024.0));
                m.put("lastAnalyzed", asString(rs.getObject("last_analyzed")));
            }
            return m;
        }, name);

        if (stats == null || stats.isEmpty()) {
            throw new IllegalArgumentException("No such table for the current user: " + name);
        }

        List<String> pkColumns = jdbc.queryForList("""
                SELECT cc.column_name
                FROM user_constraints c
                JOIN user_cons_columns cc ON cc.constraint_name = c.constraint_name
                WHERE c.constraint_type = 'P' AND c.table_name = ?
                ORDER BY cc.position
                """, String.class, name);

        List<Map<String, Object>> columns = jdbc.query("""
                SELECT column_id, column_name, data_type, data_length,
                       data_precision, data_scale, nullable
                FROM user_tab_columns
                WHERE table_name = ?
                ORDER BY column_id
                """, (rs, i) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            String colName = rs.getString("column_name");
            c.put("columnId", rs.getObject("column_id"));
            c.put("columnName", colName);
            c.put("dataType", rs.getString("data_type"));
            c.put("type", formatType(rs.getString("data_type"),
                    (Number) rs.getObject("data_length"),
                    (Number) rs.getObject("data_precision"),
                    (Number) rs.getObject("data_scale")));
            c.put("nullable", "Y".equals(rs.getString("nullable")));
            c.put("primaryKey", pkColumns.contains(colName));
            return c;
        }, name);

        List<Map<String, Object>> indexes = jdbc.query("""
                SELECT i.index_name, i.uniqueness, i.status,
                       LISTAGG(ic.column_name, ', ')
                         WITHIN GROUP (ORDER BY ic.column_position) AS cols
                FROM user_indexes i
                JOIN user_ind_columns ic ON ic.index_name = i.index_name
                WHERE i.table_name = ?
                GROUP BY i.index_name, i.uniqueness, i.status
                ORDER BY i.index_name
                """, (rs, i) -> {
            Map<String, Object> ix = new LinkedHashMap<>();
            ix.put("indexName", rs.getString("index_name"));
            ix.put("uniqueness", rs.getString("uniqueness"));
            ix.put("status", rs.getString("status"));
            ix.put("columns", rs.getString("cols"));
            return ix;
        }, name);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tableName", name);
        out.putAll(stats);
        out.put("primaryKey", pkColumns);
        out.put("columns", columns);
        out.put("indexes", indexes);
        return out;
    }

    /**
     * Exact {@code COUNT(*)} — deliberately separate from the stats-based estimate
     * so the workshop can contrast the two. The name is whitelisted against
     * USER_TABLES and pattern-checked before being used as an identifier, since a
     * table name cannot be a bind variable.
     */
    public Map<String, Object> exactCount(String rawName) {
        String name = normalize(rawName);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tables WHERE table_name = ?", Integer.class, name);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("No such table for the current user: " + name);
        }
        String sql = "SELECT COUNT(*) FROM \"" + name + "\"";
        log.info("Schema: exact count {}", sql);
        long startNs = System.nanoTime();
        Long count = jdbc.queryForObject(sql, Long.class);
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tableName", name);
        out.put("count", count);
        out.put("elapsedMs", elapsedMs);
        return out;
    }

    private String normalize(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("Table name is required");
        }
        String name = rawName.trim().toUpperCase();
        if (!SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + rawName);
        }
        return name;
    }

    private static String formatType(String type, Number length, Number precision, Number scale) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "RAW" ->
                    length == null ? type : type + "(" + length.longValue() + ")";
            case "NUMBER" -> {
                if (precision == null) {
                    yield "NUMBER";
                }
                yield (scale == null || scale.intValue() == 0)
                        ? "NUMBER(" + precision.longValue() + ")"
                        : "NUMBER(" + precision.longValue() + "," + scale.longValue() + ")";
            }
            default -> type;
        };
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
