package com.f9n.altibase.exporter;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MultiCollector that runs custom SQL from config; each row → one gauge data point (labels + value).
 *
 * <p>Jobs without an interval run synchronously at scrape time on the shared connection. Jobs with an
 * interval run in the background on a dedicated connection at that cadence; {@code collect()} then serves
 * the cached snapshots, decoupling DB load from the Prometheus scrape frequency (sql_exporter style).
 */
public final class CustomQueryCollector implements MultiCollector, AutoCloseable {

    /** Mandatory prefix for all custom query metric names; avoids clash with built-in altibase_* metrics. */
    public static final String CUSTOM_METRIC_PREFIX = "altibase_custom_";

    private static final Logger log = LoggerFactory.getLogger(CustomQueryCollector.class);

    private final Connection scrapeConn;
    private final Connection backgroundConn;
    private final List<QueryDef> scrapeQueries;
    private final List<JobDef> backgroundJobs;
    private final List<String> metricNames;
    /** Latest snapshots per background job name; read on scrape, written by the scheduler. */
    private final Map<String, List<MetricSnapshot>> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    /**
     * @param scrapeConn     shared connection used for interval-less (scrape-time) jobs
     * @param backgroundConn dedicated connection for interval jobs; required iff any job has an interval
     * @param jobs           parsed jobs (each with its queries and optional interval)
     */
    public CustomQueryCollector(Connection scrapeConn, Connection backgroundConn, List<JobDef> jobs) {
        this.scrapeConn = scrapeConn;
        this.backgroundConn = backgroundConn;

        List<QueryDef> scrape = new ArrayList<>();
        List<JobDef> background = new ArrayList<>();
        for (JobDef job : jobs) {
            if (job.interval() == null) scrape.addAll(job.queries());
            else background.add(job);
        }
        this.scrapeQueries = List.copyOf(scrape);
        this.backgroundJobs = List.copyOf(background);
        this.metricNames = jobs.stream()
                .flatMap(j -> j.queries().stream())
                .flatMap(q -> metricNames(q).stream())
                .distinct()
                .toList();

        if (backgroundJobs.isEmpty()) {
            this.scheduler = null;
        } else {
            if (backgroundConn == null) {
                throw new IllegalArgumentException("backgroundConn is required when jobs declare an interval");
            }
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "altibase-custom-collect");
                t.setDaemon(true);
                return t;
            });
            for (JobDef job : backgroundJobs) {
                scheduler.scheduleAtFixedRate(() -> refreshJob(job), 0, job.interval().toMillis(), TimeUnit.MILLISECONDS);
                log.info("Custom query job scheduled: name={} interval={}s queries={}", job.name(), job.interval().toSeconds(), job.queries().size());
            }
        }
    }

    /** Background task: re-run a job's queries and replace its cached snapshots. */
    private void refreshJob(JobDef job) {
        long start = System.nanoTime();
        List<MetricSnapshot> snapshots = new ArrayList<>();
        int failed = 0;
        for (QueryDef q : job.queries()) {
            try {
                runQuery(backgroundConn, q, snapshots);
            } catch (Exception e) {
                failed++;
                log.warn("Custom query failed (background job={}): name={} error={}", job.name(), customMetricName(q.name()), e.getMessage());
            }
        }
        cache.put(job.name(), List.copyOf(snapshots));
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        log.info("Custom query job collected: name={} queries={} metrics={} failed={} duration_seconds={}",
                job.name(), job.queries().size(), snapshots.size(), failed,
                String.format(Locale.ROOT, "%.3f", seconds));
    }

    private static String customMetricName(String name) {
        if (name == null || name.isEmpty()) return CUSTOM_METRIC_PREFIX + "unnamed";
        return name.startsWith(CUSTOM_METRIC_PREFIX) ? name : CUSTOM_METRIC_PREFIX + name;
    }

    /** Metric name for one value column: altibase_custom_<query>_<value> (single-value/legacy queries use just the query name). */
    private static String customMetricName(String name, String valueColumn) {
        return customMetricName(name) + "_" + sanitizeLabelName(valueColumn);
    }

    /** All Prometheus metric names a query produces: one per value column, or a single name in legacy mode. */
    private static List<String> metricNames(QueryDef q) {
        List<String> values = q.valueColumns();
        if (values == null || values.isEmpty()) return List.of(customMetricName(q.name()));
        return values.stream().map(v -> customMetricName(q.name(), v)).toList();
    }

    @Override
    public MetricSnapshots collect() {
        List<MetricSnapshot> snapshots = new ArrayList<>();
        for (QueryDef q : scrapeQueries) {
            try {
                runQuery(scrapeConn, q, snapshots);
            } catch (Exception e) {
                log.warn("Custom query failed: name={} error={}", customMetricName(q.name()), e.getMessage());
            }
        }
        for (List<MetricSnapshot> cached : cache.values()) snapshots.addAll(cached);
        return new MetricSnapshots(snapshots);
    }

    @Override
    public List<String> getPrometheusNames() {
        return List.copyOf(metricNames);
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void runQuery(Connection conn, QueryDef q, List<MetricSnapshot> out) throws SQLException {
        List<String> valueCols = q.valueColumns();
        if (valueCols == null || valueCols.isEmpty()) {
            runSingleValueQuery(conn, q, out);
        } else {
            runMultiValueQuery(conn, q, valueCols, out);
        }
    }

    /** Legacy mode: no explicit `values`; one metric named after the query, value column inferred. */
    private void runSingleValueQuery(Connection conn, QueryDef q, List<MetricSnapshot> out) throws SQLException {
        List<GaugeSnapshot.GaugeDataPointSnapshot> points = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(q.sql())) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> labelCols = q.labelColumns() != null ? q.labelColumns() : inferLabelColumns(meta, colCount);
            int valueColIndex = findValueColumn(meta, colCount, labelCols);

            while (rs.next()) {
                Labels labels = buildLabels(rs, meta, labelCols);
                double value = getNumeric(rs, valueColIndex);
                points.add(new GaugeSnapshot.GaugeDataPointSnapshot(value, labels, null));
            }
        }
        addMetric(out, customMetricName(q.name()), q.help(), points);
    }

    /** `values` mode: one metric per value column (altibase_custom_<query>_<value>); labels = explicit or all non-value columns. */
    private void runMultiValueQuery(Connection conn, QueryDef q, List<String> valueCols, List<MetricSnapshot> out) throws SQLException {
        List<List<GaugeSnapshot.GaugeDataPointSnapshot>> pointsPerValue = new ArrayList<>();
        for (int i = 0; i < valueCols.size(); i++) pointsPerValue.add(new ArrayList<>());

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(q.sql())) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> labelCols = q.labelColumns() != null ? q.labelColumns() : inferLabelColumns(meta, colCount, valueCols);
            int[] valueColIndexes = new int[valueCols.size()];
            for (int i = 0; i < valueCols.size(); i++) valueColIndexes[i] = findColumn(meta, valueCols.get(i));

            while (rs.next()) {
                Labels labels = buildLabels(rs, meta, labelCols);
                for (int i = 0; i < valueCols.size(); i++) {
                    if (valueColIndexes[i] < 1) continue;
                    double value = getNumeric(rs, valueColIndexes[i]);
                    pointsPerValue.get(i).add(new GaugeSnapshot.GaugeDataPointSnapshot(value, labels, null));
                }
            }
        }
        for (int i = 0; i < valueCols.size(); i++) {
            addMetric(out, customMetricName(q.name(), valueCols.get(i)), q.help(), pointsPerValue.get(i));
        }
    }

    private static Labels buildLabels(ResultSet rs, ResultSetMetaData meta, List<String> labelCols) throws SQLException {
        List<String> pairs = new ArrayList<>();
        for (String labelName : labelCols) {
            String val = getString(rs, meta, labelName);
            pairs.add(sanitizeLabelName(labelName));
            pairs.add(Objects.requireNonNullElse(val, ""));
        }
        return pairs.isEmpty() ? Labels.EMPTY : Labels.of(pairs.toArray(new String[0]));
    }

    private static void addMetric(List<MetricSnapshot> out, String name, String help,
                                  List<GaugeSnapshot.GaugeDataPointSnapshot> points) {
        if (points.isEmpty()) return;
        GaugeSnapshot.Builder b = GaugeSnapshot.builder().name(name).help(help);
        for (var p : points) b.dataPoint(p);
        out.add(b.build());
    }

    private static List<String> inferLabelColumns(ResultSetMetaData meta, int colCount) throws SQLException {
        List<String> labelCols = new ArrayList<>();
        for (int i = 1; i < colCount; i++) {
            labelCols.add(meta.getColumnLabel(i));
        }
        return labelCols;
    }

    /** Infer labels as every column that is not one of the declared value columns (case-insensitive). */
    private static List<String> inferLabelColumns(ResultSetMetaData meta, int colCount, List<String> valueCols) throws SQLException {
        List<String> labelCols = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            String label = meta.getColumnLabel(i);
            boolean isValue = false;
            for (String v : valueCols) {
                if (v.equalsIgnoreCase(label)) { isValue = true; break; }
            }
            if (!isValue) labelCols.add(label);
        }
        return labelCols;
    }

    /** 1-based index of the column with the given label (case-insensitive), or -1 if absent. */
    private static int findColumn(ResultSetMetaData meta, String columnLabel) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (columnLabel.equalsIgnoreCase(meta.getColumnLabel(i))) return i;
        }
        return -1;
    }

    private static int findValueColumn(ResultSetMetaData meta, int colCount, List<String> labelCols) throws SQLException {
        for (int i = 1; i <= colCount; i++) {
            String label = meta.getColumnLabel(i);
            if ("value".equalsIgnoreCase(label)) return i;
            boolean isLabel = false;
            for (String lc : labelCols) {
                if (lc.equalsIgnoreCase(label)) { isLabel = true; break; }
            }
            if (!isLabel) return i;
        }
        return colCount;
    }

    private static String getString(ResultSet rs, ResultSetMetaData meta, String columnLabel) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (columnLabel.equalsIgnoreCase(meta.getColumnLabel(i))) {
                Object o = rs.getObject(i);
                return o != null ? o.toString() : "";
            }
        }
        return "";
    }

    private static double getNumeric(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getDouble(columnIndex);
    }

    private static String sanitizeLabelName(String name) {
        return name == null ? "" : name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    /** A group of queries sharing an optional collection interval. interval == null → run at scrape time. */
    public record JobDef(String name, Duration interval, List<QueryDef> queries) {
        public JobDef {
            queries = List.copyOf(queries);
        }

        @Override
        public List<QueryDef> queries() {
            return List.copyOf(queries);
        }
    }

    public record QueryDef(String name, String help, String sql, List<String> labelColumns, List<String> valueColumns) {
        public QueryDef {
            labelColumns = labelColumns == null ? null : List.copyOf(labelColumns);
            valueColumns = valueColumns == null ? null : List.copyOf(valueColumns);
        }

        /** Legacy single-value query (no explicit `values`). */
        public QueryDef(String name, String help, String sql, List<String> labelColumns) {
            this(name, help, sql, labelColumns, null);
        }

        @Override
        public List<String> labelColumns() {
            return labelColumns == null ? null : List.copyOf(labelColumns);
        }

        @Override
        public List<String> valueColumns() {
            return valueColumns == null ? null : List.copyOf(valueColumns);
        }
    }
}
