package com.example.oraclebench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks which course datasets (HR, SH, the TUNER work copies, the benchmark
 * table) are actually present and visible to the connected user. Powers the
 * green/red readiness list in the UI: green = present, red = missing (or not
 * visible to this user — relevant if you are not connected as the grantee).
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    /** Shape mirrors datasets.json. */
    public record DatasetGroup(String group, String owner, List<Item> items) {
        public record Item(String name, Long expectedRows) {
        }
    }

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private List<DatasetGroup> spec = List.of();

    public DatasetService(ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void load() throws IOException {
        try (InputStream in = new ClassPathResource("datasets.json").getInputStream()) {
            this.spec = mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, DatasetGroup.class));
        }
        log.info("Loaded {} dataset groups", spec.size());
    }

    /** Resolves each expected object against ALL_TABLES for the current session. */
    public List<Map<String, Object>> readiness() {
        String currentUser = jdbc.queryForObject("SELECT USER FROM dual", String.class);

        return spec.stream().map(g -> {
            String owner = "$USER".equals(g.owner()) ? currentUser : g.owner();
            List<Map<String, Object>> items = g.items().stream().map(it -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", it.name());
                m.put("owner", owner);
                m.put("expectedRows", it.expectedRows());
                Long numRows = lookup(owner, it.name());
                m.put("present", numRows != null || existsWithoutStats(owner, it.name()));
                m.put("numRows", numRows);
                return m;
            }).toList();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("group", g.group());
            group.put("owner", owner);
            group.put("items", items);
            return group;
        }).toList();
    }

    private Long lookup(String owner, String table) {
        // Oracle NUMBER arrives as BigDecimal; num_rows may also be NULL (never analyzed).
        List<Long> rows = jdbc.query(
                "SELECT num_rows FROM all_tables WHERE owner = ? AND table_name = ?",
                (rs, i) -> {
                    Number n = (Number) rs.getObject("num_rows");
                    return n == null ? null : n.longValue();
                }, owner, table);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean existsWithoutStats(String owner, String table) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM all_tables WHERE owner = ? AND table_name = ?",
                Integer.class, owner, table);
        return c != null && c > 0;
    }
}
