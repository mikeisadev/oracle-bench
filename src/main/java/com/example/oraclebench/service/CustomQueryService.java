package com.example.oraclebench.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backs the "Query custom" tab: pickers for the tables/columns the user can see,
 * and a <b>read-only</b> runner for free-hand SELECTs. The runner rejects anything
 * that is not a single SELECT/WITH statement and opens the connection read-only, so
 * the editor cannot be used to run DML/DDL by accident.
 */
@Service
public class CustomQueryService {

    private static final Logger log = LoggerFactory.getLogger(CustomQueryService.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Z0-9_$#]+$");
    private static final int MAX_DATA_ROWS = 500;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public CustomQueryService(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** Tables the current user can see, limited to their own schema plus HR and SH. */
    public List<Map<String, Object>> visibleTables() {
        return jdbc.query("""
                SELECT owner, table_name
                FROM all_tables
                WHERE owner IN (USER, 'HR', 'SH')
                ORDER BY DECODE(owner, USER, 0, 1), owner, table_name
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", rs.getString("owner"));
            m.put("table", rs.getString("table_name"));
            return m;
        });
    }

    /** Columns of one table, for the column picker / "SELECT *" expansion. */
    public List<Map<String, Object>> columns(String rawOwner, String rawTable) {
        String owner = ident(rawOwner);
        String table = ident(rawTable);
        return jdbc.query("""
                SELECT column_name, data_type
                FROM all_tab_columns
                WHERE owner = ? AND table_name = ?
                ORDER BY column_id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.getString("column_name"));
            m.put("dataType", rs.getString("data_type"));
            return m;
        }, owner, table);
    }

    public record CustomResult(List<String> columns, List<Map<String, Object>> data,
                               long rows, double elapsedMs, String sqlId, boolean truncated) {
    }

    /** Executes a read-only SELECT (or a translated meta-command) and returns rows. */
    public CustomResult run(String rawSql) throws SQLException {
        String meta = translateMeta(rawSql);
        String sql = (meta != null) ? meta : requireReadOnly(rawSql);
        log.info("Custom query{}: {}", meta != null ? " (meta)" : "", sql);

        try (Connection conn = dataSource.getConnection()) {
            boolean prevReadOnly = conn.isReadOnly();
            try {
                conn.setReadOnly(true);
            } catch (SQLException ignore) {
                // some drivers reject setReadOnly on an active connection; validation still guards us
            }
            try {
                List<String> columns = new java.util.ArrayList<>();
                List<Map<String, Object>> data = new java.util.ArrayList<>();
                boolean truncated = false;
                long rows = 0;

                double elapsedMs;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    long startNs = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        String[] names = columnNames(rs, columns);
                        while (rs.next()) {
                            rows++;
                            if (rows <= MAX_DATA_ROWS) {
                                data.add(row(rs, names));
                            } else {
                                truncated = true;
                            }
                        }
                    }
                    elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
                }

                String sqlId = lookupSqlId(conn, sql);
                return new CustomResult(columns, data, rows, elapsedMs, sqlId, truncated);
            } finally {
                try {
                    conn.setReadOnly(prevReadOnly);
                } catch (SQLException ignore) {
                    // ignore reset failures before returning to the pool
                }
            }
        }
    }

    // ---------- helpers ----------

    // ---------- SQL*Plus / SQLcl style meta-commands (translated to SQL) ----------

    private static final Pattern P_DESC = Pattern.compile("^(?:desc|describe)\\s+([A-Za-z0-9_$#.]+)\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SHOW_USER = Pattern.compile("^show\\s+user\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SHOW_CON = Pattern.compile("^show\\s+con_name\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SHOW_PARAM = Pattern.compile("^show\\s+parameters?\\s*([A-Za-z0-9_$#]*)\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SHOW_PDBS = Pattern.compile("^show\\s+pdbs\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SHOW_TABLES = Pattern.compile("^(?:show\\s+tables|tables)\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DDL = Pattern.compile("^ddl\\s+([A-Za-z0-9_$#.]+)\\s*;?$", Pattern.CASE_INSENSITIVE);

    /**
     * Translates a handful of SQL*Plus/SQLcl client commands into runnable SQL so
     * developers/DBAs can type them straight into the editor. Returns {@code null}
     * when the input is not a recognized meta-command. Identifiers are validated and
     * embedded as string literals (compared as data, never as SQL identifiers).
     */
    private String translateMeta(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip();
        Matcher m;

        if ((m = P_DESC.matcher(t)).matches()) {
            String[] on = ownerAndName(m.group(1));
            String ownerPred = on[0] == null ? "owner = USER" : "owner = '" + on[0] + "'";
            return """
                    SELECT column_id AS "#", column_name AS "COLONNA",
                      CASE WHEN data_type IN ('VARCHAR2','CHAR','NVARCHAR2','NCHAR','RAW')
                             THEN data_type||'('||data_length||')'
                           WHEN data_type='NUMBER' AND data_precision IS NOT NULL
                             THEN data_type||'('||data_precision||CASE WHEN NVL(data_scale,0)>0 THEN ','||data_scale END||')'
                           ELSE data_type END AS "TIPO",
                      DECODE(nullable,'N','NOT NULL','') AS "NULL?"
                    FROM all_tab_columns
                    WHERE table_name = '%s' AND %s
                    ORDER BY column_id"""
                    .formatted(on[1], ownerPred);
        }
        if (P_SHOW_USER.matcher(t).matches()) {
            return "SELECT USER AS \"USER\" FROM dual";
        }
        if (P_SHOW_CON.matcher(t).matches()) {
            return "SELECT SYS_CONTEXT('USERENV','CON_NAME') AS \"CON_NAME\" FROM dual";
        }
        if ((m = P_SHOW_PARAM.matcher(t)).matches()) {
            String name = m.group(1);
            String pred = (name == null || name.isBlank())
                    ? "1 = 1"
                    : "name LIKE '%" + ident(name).toLowerCase() + "%'";
            return "SELECT name AS \"NAME\", value AS \"VALUE\" FROM v$parameter WHERE " + pred + " ORDER BY name";
        }
        if (P_SHOW_PDBS.matcher(t).matches()) {
            return "SELECT con_id AS \"CON_ID\", name AS \"NAME\", open_mode AS \"OPEN_MODE\" FROM v$pdbs ORDER BY con_id";
        }
        if (P_SHOW_TABLES.matcher(t).matches()) {
            return "SELECT table_name AS \"TABLE_NAME\", num_rows AS \"NUM_ROWS\" FROM user_tables ORDER BY table_name";
        }
        if ((m = P_DDL.matcher(t)).matches()) {
            String[] on = ownerAndName(m.group(1));
            String owner = on[0] == null ? "USER" : "'" + on[0] + "'";
            return "SELECT DBMS_METADATA.GET_DDL(\n"
                    + "  (SELECT object_type FROM all_objects WHERE object_name='" + on[1] + "'\n"
                    + "     AND owner=" + owner + " AND object_type IN ('TABLE','VIEW','INDEX','SEQUENCE') AND ROWNUM=1),\n"
                    + "  '" + on[1] + "', " + owner + ") AS \"DDL\" FROM dual";
        }
        return null;
    }

    /** Splits {@code owner.name} (owner may be null) and validates both parts. */
    private String[] ownerAndName(String raw) {
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            return new String[]{ident(raw.substring(0, dot)), ident(raw.substring(dot + 1))};
        }
        return new String[]{null, ident(raw)};
    }

    /** Allows a single SELECT/WITH statement only; strips one trailing semicolon. */
    public String requireReadOnly(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("Nessuna SQL fornita");
        }
        String sql = rawSql.strip();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("Un solo statement per volta (niente ';' multipli)");
        }
        String head = sql.replaceFirst("^(?is)(\\s*--[^\\n]*\\n|\\s*/\\*.*?\\*/)*", "").stripLeading().toLowerCase();
        if (!head.startsWith("select") && !head.startsWith("with")) {
            throw new IllegalArgumentException("Sono ammesse solo query in lettura: inizia con SELECT o WITH");
        }
        return sql;
    }

    private String[] columnNames(ResultSet rs, List<String> columns) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] names = new String[n];
        for (int i = 1; i <= n; i++) {
            names[i - 1] = md.getColumnLabel(i);
            columns.add(names[i - 1]);
        }
        return names;
    }

    private Map<String, Object> row(ResultSet rs, String[] names) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            Object value = rs.getObject(i + 1);
            if (value instanceof java.sql.Clob clob) {
                // e.g. DBMS_METADATA.GET_DDL — read the CLOB content (capped)
                long len = clob.length();
                value = clob.getSubString(1, (int) Math.min(len, 1_000_000));
            } else if (value != null && !(value instanceof Number) && !(value instanceof String)
                    && !(value instanceof Boolean)) {
                value = value.toString();
            }
            row.put(names[i], value);
        }
        return row;
    }

    private String lookupSqlId(Connection conn, String sql) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT sql_id FROM v$sql WHERE sql_text = ?")) {
            ps.setString(1, sql);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.debug("V$SQL lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private String ident(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Identificatore mancante");
        }
        String id = raw.trim().toUpperCase();
        if (!SAFE_IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("Identificatore non valido: " + raw);
        }
        return id;
    }
}
