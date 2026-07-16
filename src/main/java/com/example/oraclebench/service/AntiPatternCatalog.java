package com.example.oraclebench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the "bad vs good" tuning anti-patterns from {@code antipatterns.json}.
 * The SQL lives entirely on the server: the UI only references entries by id and
 * variant, so no arbitrary SQL from the client is ever executed.
 */
@Component
public class AntiPatternCatalog {

    private static final Logger log = LoggerFactory.getLogger(AntiPatternCatalog.class);

    /** One catalog entry. {@code needsIndexOn} may be null. */
    public record AntiPattern(String id, String title, String problem, String fix,
                              String badSql, String goodSql, String needsIndexOn) {
    }

    private final ObjectMapper mapper;
    private Map<String, AntiPattern> byId = Map.of();

    public AntiPatternCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() throws IOException {
        try (InputStream in = new ClassPathResource("antipatterns.json").getInputStream()) {
            List<AntiPattern> list = mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, AntiPattern.class));
            Map<String, AntiPattern> map = new LinkedHashMap<>();
            for (AntiPattern ap : list) {
                map.put(ap.id(), ap);
            }
            this.byId = map;
        }
        log.info("Loaded {} anti-patterns: {}", byId.size(), byId.keySet());
    }

    public List<AntiPattern> all() {
        return List.copyOf(byId.values());
    }

    public AntiPattern get(String id) {
        AntiPattern ap = byId.get(id);
        if (ap == null) {
            throw new IllegalArgumentException("No such anti-pattern: " + id);
        }
        return ap;
    }

    /** Returns the SQL text for the requested variant ("bad" or "good"). */
    public String sqlFor(String id, String variant) {
        AntiPattern ap = get(id);
        return "good".equalsIgnoreCase(variant) ? ap.goodSql() : ap.badSql();
    }
}
