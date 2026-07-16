package com.example.oraclebench.web;

import com.example.oraclebench.service.AntiPatternCatalog;
import com.example.oraclebench.service.CustomQueryService;
import com.example.oraclebench.service.QueryService;
import com.example.oraclebench.service.QueryService.BuiltStatement;
import com.example.oraclebench.service.TuningService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tuning")
public class TuningController {

    private final TuningService tuning;
    private final QueryService queryService;
    private final AntiPatternCatalog catalog;
    private final CustomQueryService custom;

    public TuningController(TuningService tuning, QueryService queryService,
                            AntiPatternCatalog catalog, CustomQueryService custom) {
        this.tuning = tuning;
        this.queryService = queryService;
        this.catalog = catalog;
        this.custom = custom;
    }

    public record SqlRequest(String sql) {
    }

    /** EXPLAIN PLAN for a free-hand, read-only SELECT from the custom editor. */
    @PostMapping("/plan")
    public ResponseEntity<?> planCustom(@RequestBody SqlRequest body) {
        try {
            String sql = custom.requireReadOnly(body == null ? null : body.sql());
            return runPlan(new BuiltStatement(-1, sql, null, false, false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Autotrace (execute + real plan + I/O stats) for a free-hand, read-only SELECT. */
    @PostMapping("/autotrace")
    public ResponseEntity<?> autotraceCustom(@RequestBody SqlRequest body) {
        try {
            String sql = custom.requireReadOnly(body == null ? null : body.sql());
            return runAutotrace(new BuiltStatement(-1, sql, null, false, false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** EXPLAIN PLAN (estimate only) for a predefined query. */
    @GetMapping("/plan")
    public ResponseEntity<?> plan(@RequestParam int id,
                                  @RequestParam(name = "p", required = false) String p) {
        return runPlan(queryService.build(id, p));
    }

    /** Autotrace (execute + real plan + I/O stats) for a predefined query. */
    @GetMapping("/autotrace")
    public ResponseEntity<?> autotrace(@RequestParam int id,
                                       @RequestParam(name = "p", required = false) String p) {
        return runAutotrace(queryService.build(id, p));
    }

    /** Cumulative statistics for a sql_id from V$SQL. */
    @GetMapping("/sqlstats/{sqlId}")
    public ResponseEntity<?> sqlStats(@PathVariable String sqlId) {
        try {
            Map<String, Object> stats = tuning.sqlStats(sqlId);
            if (stats == null) {
                return ResponseEntity.status(404).body(Map.of("error", "sql_id non presente in V$SQL: " + sqlId));
            }
            return ResponseEntity.ok(stats);
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", root(e)));
        }
    }

    /** The anti-pattern catalog (SQL included, for display). */
    @GetMapping("/antipatterns")
    public ResponseEntity<?> antipatterns() {
        return ResponseEntity.ok(catalog.all());
    }

    /** EXPLAIN PLAN for one variant ("bad"/"good") of a catalog anti-pattern. */
    @GetMapping("/antipatterns/{id}/plan")
    public ResponseEntity<?> antiPatternPlan(@PathVariable String id,
                                             @RequestParam(defaultValue = "bad") String variant) {
        try {
            return runPlan(rawStatement(catalog.sqlFor(id, variant)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Autotrace for one variant of a catalog anti-pattern. */
    @GetMapping("/antipatterns/{id}/autotrace")
    public ResponseEntity<?> antiPatternAutotrace(@PathVariable String id,
                                                  @RequestParam(defaultValue = "bad") String variant) {
        try {
            return runAutotrace(rawStatement(catalog.sqlFor(id, variant)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- shared ----------

    private BuiltStatement rawStatement(String sql) {
        return new BuiltStatement(-1, sql, null, false, false);
    }

    private ResponseEntity<?> runPlan(BuiltStatement stmt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sql", stmt.sql());
            body.put("plan", tuning.explainPlan(stmt));
            return ResponseEntity.ok(body);
        } catch (SQLException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> runAutotrace(BuiltStatement stmt) {
        try {
            TuningService.AutotraceResult r = tuning.autotrace(stmt);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sql", stmt.sql());
            body.put("sqlId", r.sqlId() == null ? "" : r.sqlId());
            body.put("rows", r.rows());
            body.put("elapsedMs", r.elapsedMs());
            body.put("plan", r.plan());
            body.put("stats", r.stats());
            return ResponseEntity.ok(body);
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
