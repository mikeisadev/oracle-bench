package com.example.oraclebench.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Lab operations that <i>change</i> the schema/data so the workshop can see cause
 * and effect: seed volume (so full scans actually hurt) and create/drop indexes
 * (so a plan can flip from FULL to INDEX). Identifiers are whitelisted against the
 * data dictionary and pattern-checked, because table/column/index names cannot be
 * bind variables.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Z0-9_$#]+$");
    private static final int MAX_SEED = 2_000_000;
    private static final int BATCH = 1_000;

    private static final String[] FIRST = {"Mario", "Luca", "Giulia", "Anna", "Marco",
            "Sara", "Paolo", "Elena", "Davide", "Chiara", "Andrea", "Francesca"};
    private static final String[] LAST = {"Rossi", "Bianchi", "Verdi", "Russo", "Ferrari",
            "Esposito", "Romano", "Colombo", "Ricci", "Marino", "Greco", "Bruno"};

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public AdminService(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** Bulk-inserts {@code n} fictitious customers into bench_customers, in batches. */
    public Map<String, Object> seedCustomers(int n) throws SQLException {
        if (n < 1 || n > MAX_SEED) {
            throw new IllegalArgumentException("n must be between 1 and " + MAX_SEED);
        }
        log.info("Seeding {} rows into bench_customers", n);
        String sql = "INSERT INTO bench_customers (name, email) VALUES (?, ?)";

        long startNs = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                // NAME is intentionally low-cardinality (12x12 = 144 distinct values) so
                // an index on it is NOT selective — a teaching case. EMAIL is unique and
                // sequential, so an index on EMAIL flips the plan cleanly (query #6).
                for (int i = 1; i <= n; i++) {
                    ps.setString(1, FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)]);
                    ps.setString(2, "cliente" + i + "@example.com");
                    ps.addBatch();
                    if (i % BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        }
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", n);
        out.put("elapsedMs", elapsedMs);
        return out;
    }

    /** Empties bench_customers (fast reset between demos). */
    public Map<String, Object> truncateCustomers() {
        log.info("Truncating bench_customers");
        long startNs = System.nanoTime();
        jdbc.execute("TRUNCATE TABLE bench_customers");
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        return Map.of("elapsedMs", elapsedMs);
    }

    /** Recomputes optimizer statistics for a table (DBMS_STATS.GATHER_TABLE_STATS). */
    public Map<String, Object> gatherStats(String rawTable) {
        String table = requireTable(rawTable);
        log.info("Gathering stats for {}", table);
        long startNs = System.nanoTime();
        jdbc.update("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, ?); END;", table);
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("elapsedMs", elapsedMs);
        return out;
    }

    /** Creates a non-unique index on {@code table(column)}. */
    public Map<String, Object> createIndex(String rawTable, String rawColumn) {
        String table = requireTable(rawTable);
        String column = requireColumn(table, rawColumn);
        String indexName = ("IX_" + table + "_" + column);
        if (indexName.length() > 128) {
            indexName = indexName.substring(0, 128);
        }
        String ddl = "CREATE INDEX \"" + indexName + "\" ON \"" + table + "\"(\"" + column + "\")";
        log.info("DDL: {}", ddl);

        long startNs = System.nanoTime();
        jdbc.execute(ddl);
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexName", indexName);
        out.put("elapsedMs", elapsedMs);
        return out;
    }

    /** Drops an index by name (refuses indexes that back a constraint, e.g. the PK). */
    public Map<String, Object> dropIndex(String rawIndex) {
        String index = normalize(rawIndex);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_indexes WHERE index_name = ?", Integer.class, index);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("No such index for the current user: " + index);
        }
        Integer constrained = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_constraints WHERE index_name = ?", Integer.class, index);
        if (constrained != null && constrained > 0) {
            throw new IllegalArgumentException("Index " + index + " backs a constraint and cannot be dropped");
        }
        String ddl = "DROP INDEX \"" + index + "\"";
        log.info("DDL: {}", ddl);
        long startNs = System.nanoTime();
        jdbc.execute(ddl);
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        return Map.of("elapsedMs", elapsedMs);
    }

    private String requireTable(String rawTable) {
        String table = normalize(rawTable);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tables WHERE table_name = ?", Integer.class, table);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("No such table for the current user: " + table);
        }
        return table;
    }

    private String requireColumn(String table, String rawColumn) {
        String column = normalize(rawColumn);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tab_columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("No such column " + column + " on " + table);
        }
        return column;
    }

    private String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Identifier is required");
        }
        String id = raw.trim().toUpperCase();
        if (!SAFE_IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid identifier: " + raw);
        }
        return id;
    }
}
