package com.example.oraclebench.web;

import com.example.oraclebench.service.AdminService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    /** Seeds N fictitious rows into bench_customers. */
    @PostMapping("/seed")
    public ResponseEntity<?> seed(@RequestParam(defaultValue = "100000") int n) {
        try {
            return ResponseEntity.ok(admin.seedCustomers(n));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SQLException | DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    /** Empties bench_customers. */
    @PostMapping("/truncate")
    public ResponseEntity<?> truncate() {
        try {
            return ResponseEntity.ok(admin.truncateCustomers());
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    /** Recomputes optimizer statistics for a table. */
    @PostMapping("/gather-stats")
    public ResponseEntity<?> gatherStats(@RequestParam String table) {
        try {
            return ResponseEntity.ok(admin.gatherStats(table));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    /** Creates a non-unique index on table(column). */
    @PostMapping("/index")
    public ResponseEntity<?> createIndex(@RequestParam String table, @RequestParam String column) {
        try {
            return ResponseEntity.ok(admin.createIndex(table, column));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    /** Drops an index by name. */
    @DeleteMapping("/index/{name}")
    public ResponseEntity<?> dropIndex(@PathVariable String name) {
        try {
            return ResponseEntity.ok(admin.dropIndex(name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    private String root(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}