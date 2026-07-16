package com.example.oraclebench.web;

import com.example.oraclebench.service.QueryRegistry;
import com.example.oraclebench.service.QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
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
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> Map.<String, Object>of("id", e.getKey(), "sql", e.getValue()))
                .toList();
    }

    /**
     * Runs predefined query {@code id}. Optional {@code p} is bound to a "?" in the
     * query (or concatenated into query 99). Responds {id, rows, elapsedMs, sqlId};
     * when {@code data=true}, also returns {columns, data, truncated}.
     */
    @GetMapping("/query")
    public ResponseEntity<?> query(@RequestParam int id,
                                   @RequestParam(name = "p", required = false) String p,
                                   @RequestParam(name = "data", defaultValue = "false") boolean includeData) {
        try {
            QueryService.QueryResult r = queryService.run(id, p, includeData);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", r.id());
            body.put("rows", r.rows());
            body.put("elapsedMs", r.elapsedMs());
            body.put("sqlId", r.sqlId() == null ? "" : r.sqlId());
            if (includeData) {
                body.put("columns", r.columns());
                body.put("data", r.data());
                body.put("truncated", r.truncated());
            }
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SQLException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
