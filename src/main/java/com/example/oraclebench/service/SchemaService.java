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
 * Read-only exploration of the schema via the Oracle data dictionary. Lists the
 * connected user's own tables plus the visible demo schemas (HR, SH), and gives
 * per-table detail (stats, columns, indexes, PK) through the {@code ALL_*} views —
 * so any visible table can be inspected with the same panel. Segment size is only
 * available for the user's own tables (via {@code USER_SEGMENTS}).
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    /** Valid unquoted Oracle identifier — whitelisted before use as an identifier. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Z0-9_$#]+$");

    private final JdbcTemplate jdbc;

    public SchemaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String currentUser() {
        return jdbc.queryForObject("SELECT USER FROM dual", String.class);
    }

    /** Tables visible to the user: own schema first, then HR and SH. */
    public List<Map<String, Object>> listTables() {
        log.info("Schema: listing tables (USER + HR + SH)");
        return jdbc.query("""
                SELECT t.owner, t.table_name, t.num_rows,
                       (SELECT s.bytes FROM user_segments s
                         WHERE t.owner = USER AND s.segment_name = t.table_name
                           AND s.segment_type = 'TABLE') AS bytes
                FROM all_tables t
                WHERE t.owner IN (USER, 'HR', 'SH')
                ORDER BY DECODE(t.owner, USER, 0, 1), t.owner, t.table_name
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            Number bytes = (Number) rs.getObject("bytes");
            row.put("owner", rs.getString("owner"));
            row.put("tableName", rs.getString("table_name"));
            row.put("numRows", rs.getObject("num_rows"));
            row.put("bytes", bytes == null ? null : bytes.longValue());
            row.put("sizeMb", bytes == null ? null : round2(bytes.longValue() / 1024.0 / 1024.0));
            return row;
        });
    }

    /** Full detail for one table (any visible schema): stats, columns, indexes, PK. */
    public Map<String, Object> tableDetail(String rawOwner, String rawName) {
        String owner = normalize(rawOwner);
        String name = normalize(rawName);
        String user = currentUser();
        boolean own = owner.equals(user);
        log.info("Schema: detail for {}.{}", owner, name);

        Map<String, Object> stats = jdbc.query("""
                SELECT num_rows, blocks, avg_row_len, last_analyzed
                FROM all_tables WHERE owner = ? AND table_name = ?
                """, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("numRows", rs.getObject("num_rows"));
                m.put("blocks", rs.getObject("blocks"));
                m.put("avgRowLen", rs.getObject("avg_row_len"));
                m.put("lastAnalyzed", asString(rs.getObject("last_analyzed")));
            }
            return m;
        }, owner, name);

        if (stats == null || stats.isEmpty()) {
            throw new IllegalArgumentException("Nessuna tabella visibile: " + owner + "." + name);
        }

        // Segment size only for own tables (USER_SEGMENTS is per-user).
        Long bytes = own ? jdbc.query(
                "SELECT bytes FROM user_segments WHERE segment_name = ? AND segment_type = 'TABLE'",
                rs -> rs.next() ? rs.getLong("bytes") : null, name) : null;

        List<String> pkColumns = jdbc.queryForList("""
                SELECT cc.column_name
                FROM all_constraints c
                JOIN all_cons_columns cc
                  ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
                WHERE c.constraint_type = 'P' AND c.owner = ? AND c.table_name = ?
                ORDER BY cc.position
                """, String.class, owner, name);

        List<Map<String, Object>> columns = jdbc.query("""
                SELECT column_id, column_name, data_type, data_length,
                       data_precision, data_scale, nullable
                FROM all_tab_columns
                WHERE owner = ? AND table_name = ?
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
        }, owner, name);

        List<Map<String, Object>> indexes = jdbc.query("""
                SELECT i.index_name, i.uniqueness, i.status,
                       LISTAGG(ic.column_name, ', ')
                         WITHIN GROUP (ORDER BY ic.column_position) AS cols
                FROM all_indexes i
                JOIN all_ind_columns ic
                  ON ic.index_owner = i.owner AND ic.index_name = i.index_name
                WHERE i.table_owner = ? AND i.table_name = ?
                GROUP BY i.index_name, i.uniqueness, i.status
                ORDER BY i.index_name
                """, (rs, i) -> {
            Map<String, Object> ix = new LinkedHashMap<>();
            ix.put("indexName", rs.getString("index_name"));
            ix.put("uniqueness", rs.getString("uniqueness"));
            ix.put("status", rs.getString("status"));
            ix.put("columns", rs.getString("cols"));
            return ix;
        }, owner, name);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("tableName", name);
        out.put("own", own);   // whether write actions (index/stats) make sense
        out.putAll(stats);
        out.put("bytes", bytes);
        out.put("sizeMb", bytes == null ? null : round2(bytes / 1024.0 / 1024.0));
        out.put("primaryKey", pkColumns);
        out.put("columns", columns);
        out.put("indexes", indexes);
        return out;
    }

    /**
     * Exact {@code COUNT(*)} — contrasts with the stats-based estimate. Owner and
     * name are whitelisted against ALL_TABLES and pattern-checked before being used
     * as identifiers, since they cannot be bind variables.
     */
    public Map<String, Object> exactCount(String rawOwner, String rawName) {
        String owner = normalize(rawOwner);
        String name = normalize(rawName);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM all_tables WHERE owner = ? AND table_name = ?",
                Integer.class, owner, name);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Nessuna tabella visibile: " + owner + "." + name);
        }
        String sql = "SELECT COUNT(*) FROM \"" + owner + "\".\"" + name + "\"";
        log.info("Schema: exact count {}", sql);
        long startNs = System.nanoTime();
        Long count = jdbc.queryForObject(sql, Long.class);
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("tableName", name);
        out.put("count", count);
        out.put("elapsedMs", elapsedMs);
        return out;
    }

    private String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Identificatore mancante");
        }
        String v = raw.trim().toUpperCase();
        if (!SAFE_IDENTIFIER.matcher(v).matches()) {
            throw new IllegalArgumentException("Identificatore non valido: " + raw);
        }
        return v;
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
