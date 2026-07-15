package com.example.oraclebench.web;

import com.example.oraclebench.service.QueryRegistry;
import com.example.oraclebench.service.QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService queryService;
    private final QueryRegistry registry;

    public QueryController(QueryService queryService, QueryRegistry registry) {
        this.queryService = queryService;
        this.registry = registry;
    }

    /** Lists the available query ids (with their SQL) so the UI can populate the select. */
    @GetMapping("/queries")
    public List<Map<String, Object>> queries() {
        return registry.all().entrySet().stream()
                .map(e -> Map.<String, Object>of("id", e.getKey(), "sql", e.getValue()))
                .toList();
    }

    /**
     * Runs predefined query {@code id}. Optional {@code p} is bound to a "?" in the
     * query (or concatenated into query 99). Responds {id, rows, elapsedMs, sqlId}.
     */
    @GetMapping("/query")
    public ResponseEntity<?> query(@RequestParam int id,
                                   @RequestParam(name = "p", required = false) String p) {
        try {
            QueryService.QueryResult r = queryService.run(id, p);
            return ResponseEntity.ok(Map.of(
                    "id", r.id(),
                    "rows", r.rows(),
                    "elapsedMs", r.elapsedMs(),
                    "sqlId", r.sqlId() == null ? "" : r.sqlId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SQLException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
