package com.example.oraclebench.web;

import com.example.oraclebench.service.DatasetService;
import com.example.oraclebench.service.SchemaService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaService schemaService;
    private final DatasetService datasetService;

    public SchemaController(SchemaService schemaService, DatasetService datasetService) {
        this.schemaService = schemaService;
        this.datasetService = datasetService;
    }

    /** Readiness of the course datasets (HR, SH, work copies, benchmark). */
    @GetMapping("/datasets")
    public ResponseEntity<?> datasets() {
        try {
            return ResponseEntity.ok(datasetService.readiness());
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", rootMessage(e)));
        }
    }

    /** Lists the tables owned by the connected user. */
    @GetMapping("/tables")
    public ResponseEntity<?> tables() {
        try {
            List<Map<String, Object>> tables = schemaService.listTables();
            return ResponseEntity.ok(tables);
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", rootMessage(e)));
        }
    }

    /** Detail of a single table: stats, columns, indexes, primary key. */
    @GetMapping("/tables/{name}")
    public ResponseEntity<?> table(@PathVariable String name) {
        try {
            return ResponseEntity.ok(schemaService.tableDetail(name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", rootMessage(e)));
        }
    }

    /** Exact COUNT(*) for a table — contrast with the stats-based estimate. */
    @GetMapping("/tables/{name}/count")
    public ResponseEntity<?> count(@PathVariable String name) {
        try {
            return ResponseEntity.ok(schemaService.exactCount(name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", rootMessage(e)));
        }
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
