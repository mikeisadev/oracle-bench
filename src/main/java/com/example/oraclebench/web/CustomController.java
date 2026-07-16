package com.example.oraclebench.web;

import com.example.oraclebench.service.CustomQueryService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/custom")
public class CustomController {

    private final CustomQueryService custom;

    public CustomController(CustomQueryService custom) {
        this.custom = custom;
    }

    /** Tables the user can see (own schema + HR + SH) for the picker. */
    @GetMapping("/tables")
    public ResponseEntity<?> tables() {
        try {
            return ResponseEntity.ok(custom.visibleTables());
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    /** Columns of a table, to build the column picker / SELECT list. */
    @GetMapping("/columns")
    public ResponseEntity<?> columns(@RequestParam String owner, @RequestParam String table) {
        try {
            return ResponseEntity.ok(custom.columns(owner, table));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    public record RunRequest(String sql) {
    }

    /** Runs a validated, read-only SELECT. */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody RunRequest body) {
        try {
            CustomQueryService.CustomResult r = custom.run(body == null ? null : body.sql());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", r.columns());
            out.put("data", r.data());
            out.put("rows", r.rows());
            out.put("elapsedMs", r.elapsedMs());
            out.put("sqlId", r.sqlId() == null ? "" : r.sqlId());
            out.put("truncated", r.truncated());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SQLException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
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
