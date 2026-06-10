package com.f9n.altibase.exporter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueriesLoaderTest {

    @Test
    void load_emptyFile_returnsEmptyList() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            Files.writeString(tmp, "");
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            assertTrue(result.isEmpty());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void load_noJobsKey_returnsEmptyList() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            Files.writeString(tmp, "other: value\n");
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            assertTrue(result.isEmpty());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void load_topLevelQueriesNotSupported_returnsEmpty() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            String yaml = """
                queries:
                  - name: test_metric
                    help: "Test help"
                    sql: "SELECT 1 AS value"
                """;
            Files.writeString(tmp, yaml);
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            assertTrue(result.isEmpty(), "top-level queries: (without jobs:) is not supported");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void load_withLabelColumns() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            String yaml = """
                jobs:
                  - name: j
                    queries:
                      - name: rep_items
                        help: "Repl items"
                        sql: "SELECT rep_name, COUNT(*) AS value FROM T GROUP BY rep_name"
                        label_columns: [rep_name]
                """;
            Files.writeString(tmp, yaml);
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            assertEquals(List.of("rep_name"), result.get(0).queries().get(0).labelColumns());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void load_withValuesAndAliases() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            String yaml = """
                jobs:
                  - name: j
                    queries:
                      - name: table_stats
                        help: "Table stats"
                        labels:
                          - schemaname
                          - relname
                        values:
                          - seq_scan
                          - idx_scan
                        query: "SELECT schemaname, relname, seq_scan, idx_scan FROM T"
                """;
            Files.writeString(tmp, yaml);
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            CustomQueryCollector.QueryDef q = result.get(0).queries().get(0);
            assertEquals("SELECT schemaname, relname, seq_scan, idx_scan FROM T", q.sql());
            assertEquals(List.of("schemaname", "relname"), q.labelColumns());
            assertEquals(List.of("seq_scan", "idx_scan"), q.valueColumns());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void load_jobsWithInterval() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            String yaml = """
                jobs:
                  - name: fast
                    interval: 30s
                    queries:
                      - name: ping
                        help: "Ping"
                        query: "SELECT 1 AS value"
                  - name: slow
                    interval: 5m
                    connections:
                      - "ignored://whatever"
                    queries:
                      - name: db_pages
                        help: "Pages"
                        values: [mem_alloc]
                        query: "SELECT MEM_ALLOC_PAGE_COUNT AS mem_alloc FROM V$DATABASE"
                  - name: ondemand
                    queries:
                      - name: lock_count
                        help: "Locks"
                        query: "SELECT COUNT(*) AS value FROM V$LOCK"
                """;
            Files.writeString(tmp, yaml);
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            assertEquals(3, result.size());
            assertEquals(Duration.ofSeconds(30), result.get(0).interval());
            assertEquals(Duration.ofMinutes(5), result.get(1).interval());
            assertNull(result.get(2).interval(), "job without interval is scrape-time");
            assertEquals("ping", result.get(0).queries().get(0).name());
            assertEquals(List.of("mem_alloc"), result.get(1).queries().get(0).valueColumns());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void load_skipsEntryWithMissingNameHelpSql() throws IOException {
        Path tmp = Files.createTempFile("queries", ".yaml");
        try {
            String yaml = """
                jobs:
                  - name: j
                    queries:
                      - name: ok
                        help: "Help"
                        sql: "SELECT 1"
                      - name: ""
                        help: "H"
                        sql: "SELECT 2"
                      - other: junk
                """;
            Files.writeString(tmp, yaml);
            List<CustomQueryCollector.JobDef> result = QueriesLoader.load(tmp);
            assertEquals(1, result.size());
            assertEquals(1, result.get(0).queries().size());
            assertEquals("ok", result.get(0).queries().get(0).name());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void parseDuration_variants() {
        assertEquals(Duration.ofSeconds(30), QueriesLoader.parseDuration("30s", "j"));
        assertEquals(Duration.ofMinutes(1), QueriesLoader.parseDuration("1m", "j"));
        assertEquals(Duration.ofHours(1), QueriesLoader.parseDuration("1h", "j"));
        assertEquals(Duration.ofMillis(500), QueriesLoader.parseDuration("500ms", "j"));
        assertEquals(Duration.ofSeconds(90), QueriesLoader.parseDuration("1m30s", "j"));
        assertNull(QueriesLoader.parseDuration(null, "j"));
        assertNull(QueriesLoader.parseDuration("  ", "j"));
        assertNull(QueriesLoader.parseDuration("abc", "j"));
        assertNull(QueriesLoader.parseDuration("5x", "j"));
        assertNull(QueriesLoader.parseDuration("0s", "j"));
    }
}
