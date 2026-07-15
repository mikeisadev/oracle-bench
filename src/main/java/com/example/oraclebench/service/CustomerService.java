package com.example.oraclebench.service;

import com.example.oraclebench.model.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD over {@code bench_customers}. Every statement uses bind variables, and
 * every operation reports the elapsed time (ms) it measured around the JDBC call.
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final DataSource dataSource;

    public CustomerService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A CRUD outcome: the affected/returned rows plus timing. */
    public record CrudResult(String operation, List<Customer> customers, int affected, double elapsedMs) {
    }

    public CrudResult create(String name, String email) throws SQLException {
        final String sql = "INSERT INTO bench_customers (name, email) VALUES (?, ?)";
        log.info("CRUD create: {} [name='{}', email='{}']", sql, name, email);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"})) {
            ps.setString(1, name);
            ps.setString(2, email);

            long startNs = System.nanoTime();
            int affected = ps.executeUpdate();
            double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;

            Long id = null;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            Customer created = new Customer(id, name, email, null);
            return new CrudResult("create", List.of(created), affected, elapsedMs);
        }
    }

    public CrudResult readByEmail(String email) throws SQLException {
        final String sql = "SELECT id, name, email, created_at FROM bench_customers WHERE email = ? ORDER BY id";
        log.info("CRUD read: {} [email='{}']", sql, email);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);

            long startNs = System.nanoTime();
            List<Customer> found = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(mapRow(rs));
                }
            }
            double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
            return new CrudResult("read", found, found.size(), elapsedMs);
        }
    }

    public CrudResult updateNameByEmail(String email, String newName) throws SQLException {
        final String sql = "UPDATE bench_customers SET name = ? WHERE email = ?";
        log.info("CRUD update: {} [name='{}', email='{}']", sql, newName, email);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, email);

            long startNs = System.nanoTime();
            int affected = ps.executeUpdate();
            double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
            return new CrudResult("update", List.of(), affected, elapsedMs);
        }
    }

    public CrudResult deleteByEmail(String email) throws SQLException {
        final String sql = "DELETE FROM bench_customers WHERE email = ?";
        log.info("CRUD delete: {} [email='{}']", sql, email);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);

            long startNs = System.nanoTime();
            int affected = ps.executeUpdate();
            double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
            return new CrudResult("delete", List.of(), affected, elapsedMs);
        }
    }

    private Customer mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String name = rs.getString("name");
        String email = rs.getString("email");
        Timestamp ts = rs.getTimestamp("created_at");
        return new Customer(id, name, email, ts == null ? null : ts.toString());
    }
}
