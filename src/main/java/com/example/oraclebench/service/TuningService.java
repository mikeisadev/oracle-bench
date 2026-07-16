package com.example.oraclebench.service;

import com.example.oraclebench.service.QueryService.BuiltStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SQL-tuning toolbox: execution plans and per-execution instrumentation.
 *
 * <ul>
 *   <li><b>EXPLAIN PLAN</b> — the optimizer's <i>estimate</i>, no execution. Needs
 *       only a PLAN_TABLE (a public synonym in modern Oracle), so it works with a
 *       plain application user.</li>
 *   <li><b>Autotrace</b> — actually runs the statement with
 *       {@code STATISTICS_LEVEL = ALL}, then shows the real plan with A-Rows
 *       (via {@code DBMS_XPLAN.DISPLAY_CURSOR}) plus the logical-I/O delta from
 *       {@code V$MYSTAT}. Needs read access to the V$ views — grant
 *       {@code SELECT_CATALOG_ROLE} to the app user.</li>
 * </ul>
 */
@Service
public class TuningService {

    private static final Logger log = LoggerFactory.getLogger(TuningService.class);

    /** Session statistics captured around each autotrace run (SQL*Plus-style). */
    private static final List<String> STAT_NAMES = List.of(
            "session logical reads", "consistent gets", "db block gets",
            "physical reads", "sorts (memory)", "sorts (disk)");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public TuningService(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    // ---------- EXPLAIN PLAN (estimate only, no execution) ----------

    public List<String> explainPlan(BuiltStatement stmt) throws SQLException {
        // Self-generated, alphanumeric only -> safe to inline (SET STATEMENT_ID needs a literal).
        String stmtId = "OB" + Long.toHexString(System.nanoTime());
        log.info("EXPLAIN PLAN [{}] FOR: {}", stmtId, stmt.sql());

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "EXPLAIN PLAN SET STATEMENT_ID = '" + stmtId + "' FOR " + stmt.sql())) {
                if (stmt.bindable()) {
                    ps.setString(1, stmt.bindParam());
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', ?, 'ALL'))")) {
                ps.setString(1, stmtId);
                return readLines(ps);
            }
        }
    }

    // ---------- Autotrace (execute + real plan with A-Rows + I/O stats) ----------

    public record AutotraceResult(String sqlId, long rows, double elapsedMs,
                                  List<String> plan, Map<String, Long> stats) {
    }

    public AutotraceResult autotrace(BuiltStatement stmt) throws SQLException {
        log.info("Autotrace: {}", stmt.sql());
        try (Connection conn = dataSource.getConnection()) {
            setStatisticsLevel(conn, "ALL");
            try {
                Map<String, Long> before = readMyStat(conn);

                long startNs = System.nanoTime();
                long rows = 0;
                try (PreparedStatement ps = conn.prepareStatement(stmt.sql())) {
                    if (stmt.bindable()) {
                        ps.setString(1, stmt.bindParam());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows++;
                        }
                    }
                }
                double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;

                // Capture the just-executed cursor BEFORE running any other query,
                // so we address the plan by exact sql_id/child rather than "prev".
                String[] cursor = prevCursor(conn); // [sql_id, child_number]
                Map<String, Long> after = readMyStat(conn);

                List<String> plan = displayCursor(conn, cursor[0], cursor[1]);
                Map<String, Long> delta = new LinkedHashMap<>();
                for (String name : STAT_NAMES) {
                    delta.put(name, after.getOrDefault(name, 0L) - before.getOrDefault(name, 0L));
                }
                return new AutotraceResult(cursor[0], rows, elapsedMs, plan, delta);
            } finally {
                setStatisticsLevel(conn, "TYPICAL");
            }
        }
    }

    // ---------- Cumulative statistics from V$SQL ----------

    public Map<String, Object> sqlStats(String sqlId) {
        log.info("V$SQL stats for sql_id={}", sqlId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT MIN(sql_id)                     AS sql_id,
                       COUNT(*)                        AS child_cursors,
                       COUNT(DISTINCT plan_hash_value) AS distinct_plans,
                       SUM(executions)                 AS executions,
                       SUM(parse_calls)                AS parse_calls,
                       SUM(buffer_gets)                AS buffer_gets,
                       SUM(disk_reads)                 AS disk_reads,
                       SUM(rows_processed)             AS rows_processed,
                       SUM(cpu_time)                   AS cpu_time_us,
                       SUM(elapsed_time)               AS elapsed_time_us
                FROM v$sql
                WHERE sql_id = ?
                HAVING COUNT(*) > 0
                """, sqlId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- helpers ----------

    private void setStatisticsLevel(Connection conn, String level) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER SESSION SET STATISTICS_LEVEL = " + level);
        }
    }

    private Map<String, Long> readMyStat(Connection conn) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        String sql = """
                SELECT n.name, s.value
                FROM v$mystat s JOIN v$statname n ON n.statistic# = s.statistic#
                WHERE n.name IN ('session logical reads','consistent gets','db block gets',
                                 'physical reads','sorts (memory)','sorts (disk)')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    private String[] prevCursor(Connection conn) throws SQLException {
        String sql = "SELECT prev_sql_id, prev_child_number FROM v$session "
                + "WHERE sid = SYS_CONTEXT('USERENV','SID')";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new String[]{rs.getString(1), rs.getString(2)};
            }
        }
        return new String[]{null, null};
    }

    private List<String> displayCursor(Connection conn, String sqlId, String childNumber) throws SQLException {
        if (sqlId == null) {
            return List.of("(nessun cursore trovato per il piano runtime)");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'ALLSTATS LAST'))")) {
            ps.setString(1, sqlId);
            if (childNumber == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, Integer.parseInt(childNumber));
            }
            return readLines(ps);
        }
    }

    private List<String> readLines(PreparedStatement ps) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
        }
        return lines;
    }
}
