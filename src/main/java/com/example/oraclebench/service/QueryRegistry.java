package com.example.oraclebench.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the predefined queries from {@code queries.sql}.
 *
 * <p>File format: a line {@code --#<n>} introduces query number {@code n};
 * every following line up to the next {@code --#} marker (or EOF) is the SQL
 * body. Blank lines and standalone comment lines inside a body are dropped.
 */
@Component
public class QueryRegistry {

    private static final Logger log = LoggerFactory.getLogger(QueryRegistry.class);
    private static final Pattern MARKER = Pattern.compile("^\\s*--#(\\d+)\\s*$");

    private final Map<Integer, String> queries = new LinkedHashMap<>();

    @PostConstruct
    void load() throws IOException {
        String raw;
        try (InputStream in = new ClassPathResource("queries.sql").getInputStream()) {
            raw = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }

        Integer current = null;
        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            Matcher m = MARKER.matcher(line);
            if (m.matches()) {
                flush(current, body);
                current = Integer.valueOf(m.group(1));
                body = new StringBuilder();
                continue;
            }
            if (current == null) {
                continue; // preamble / header comments before the first marker
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue; // skip blank lines and inline comments
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(line);
        }
        flush(current, body);

        log.info("Loaded {} predefined queries: {}", queries.size(), queries.keySet());
    }

    private void flush(Integer id, StringBuilder body) {
        if (id == null) {
            return;
        }
        String sql = body.toString().strip();
        if (!sql.isEmpty()) {
            queries.put(id, sql);
        }
    }

    /** Returns the SQL text for the given id, or {@code null} if undefined. */
    public String get(int id) {
        return queries.get(id);
    }

    public Set<Integer> ids() {
        return queries.keySet();
    }

    public Map<Integer, String> all() {
        return Map.copyOf(queries);
    }
}
