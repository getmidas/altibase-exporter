package com.f9n.altibase.exporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads custom query definitions from YAML (sql_exporter "jobs" shape):
 *
 * <pre>
 * jobs:
 *   - name: custom
 *     interval: 1m            # optional; omitted → run at scrape time
 *     # connections: [...]    # ignored; the exporter uses the single connection from env
 *     queries: [ {name, help, query|sql, labels|label_columns?, values?}, ... ]
 * </pre>
 */
public final class QueriesLoader {

    private static final Logger log = LoggerFactory.getLogger(QueriesLoader.class);
    /** Go-style duration tokens, e.g. 30s, 1m, 1h30m, 500ms. */
    private static final Pattern DURATION_TOKEN = Pattern.compile("(\\d+)(ms|s|m|h)");

    private QueriesLoader() {}

    @SuppressWarnings("unchecked")
    public static List<CustomQueryCollector.JobDef> load(Path path) throws IOException {
        String content = Files.readString(path);
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        if (root == null) return List.of();

        Object jobsObj = root.get("jobs");
        if (jobsObj instanceof List) {
            List<CustomQueryCollector.JobDef> jobs = parseJobs((List<Map<String, Object>>) jobsObj);
            log.debug("Loaded {} custom query job(s) from {}", jobs.size(), path);
            return jobs;
        }

        if (root.get("queries") != null) {
            log.warn("Custom queries file {} uses a top-level 'queries:' key; wrap queries under 'jobs:' (see examples/queries.yaml). Ignoring.", path);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<CustomQueryCollector.JobDef> parseJobs(List<Map<String, Object>> jobs) {
        List<CustomQueryCollector.JobDef> result = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            String name = getString(job, "name");
            if (name == null) name = "job" + result.size();
            Duration interval = parseDuration(getString(job, "interval"), name);
            if (job.get("connections") != null) {
                log.debug("Ignoring 'connections' in job '{}': the exporter uses the single connection from ALTIBASE_* env", name);
            }
            Object queriesObj = job.get("queries");
            if (!(queriesObj instanceof List)) {
                log.debug("Skipping job '{}' with no queries", name);
                continue;
            }
            List<CustomQueryCollector.QueryDef> queries = parseQueries((List<Map<String, Object>>) queriesObj);
            if (queries.isEmpty()) continue;
            result.add(new CustomQueryCollector.JobDef(name, interval, queries));
        }
        return result;
    }

    private static List<CustomQueryCollector.QueryDef> parseQueries(List<Map<String, Object>> list) {
        List<CustomQueryCollector.QueryDef> result = new ArrayList<>();
        for (Map<String, Object> entry : list) {
            String name = getString(entry, "name");
            String help = getString(entry, "help");
            String sql = firstNonNull(getString(entry, "sql"), getString(entry, "query"));
            if (name == null || help == null || sql == null) {
                log.debug("Skipping query entry with missing name/help/sql: {}", entry);
                continue;
            }
            List<String> labelColumns = firstNonNull(getStringList(entry, "label_columns"), getStringList(entry, "labels"));
            List<String> valueColumns = getStringList(entry, "values");
            result.add(new CustomQueryCollector.QueryDef(name, help, sql, labelColumns, valueColumns));
        }
        return result;
    }

    /** Parse a Go-style duration ("30s", "1m", "1h30m"); null/blank → null (scrape-time); invalid → null with a warning. */
    static Duration parseDuration(String value, String jobName) {
        if (value == null || value.isBlank()) return null;
        String s = value.trim().toLowerCase(Locale.ROOT);
        Matcher m = DURATION_TOKEN.matcher(s);
        long totalMs = 0;
        int matchedEnd = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "ms" -> totalMs += n;
                case "s" -> totalMs += n * 1_000L;
                case "m" -> totalMs += n * 60_000L;
                case "h" -> totalMs += n * 3_600_000L;
                default -> { }
            }
            matchedEnd = m.end();
        }
        if (!any || matchedEnd != s.length() || totalMs <= 0) {
            log.warn("Invalid interval '{}' in job '{}'; ignoring (queries will run at scrape time)", value, jobName);
            return null;
        }
        return Duration.ofMillis(totalMs);
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isBlank() ? null : s;
    }

    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object e : (List<?>) v) {
                if (e != null) out.add(e.toString().trim());
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }
}
