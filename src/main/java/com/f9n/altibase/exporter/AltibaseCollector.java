package com.f9n.altibase.exporter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AltibaseCollector implements MultiCollector {

    private static final Logger log = LoggerFactory.getLogger(AltibaseCollector.class);
    private final Connection conn;
    private final Set<String> disabledMetrics;
    private final String exporterVersion;
    private volatile String lastVersion = "unknown";

    static final class ScrapeContext {
        private final Statement statement;
        private final Map<String, List<GaugeSnapshot.GaugeDataPointSnapshot>> points = new LinkedHashMap<>();

        ScrapeContext(Statement statement) {
            this.statement = statement;
        }

        ScrapeContext() {
            this(null);
        }

        Statement statement() {
            return statement;
        }

        void addGauge(String key, double value) {
            addGauge(key, Labels.EMPTY, value);
        }

        void addGauge(String key, Labels labels, double value) {
            points.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new GaugeSnapshot.GaugeDataPointSnapshot(value, labels, null));
        }

        List<MetricSnapshot> buildSnapshots() {
            List<MetricSnapshot> out = new ArrayList<>();
            for (Map.Entry<String, List<GaugeSnapshot.GaugeDataPointSnapshot>> e : points.entrySet()) {
                String key = e.getKey();
                String name = AltibaseMetricDefs.name(key);
                String help = AltibaseMetricDefs.help(key);
                GaugeSnapshot.Builder b = GaugeSnapshot.builder().name(name).help(help);
                for (GaugeSnapshot.GaugeDataPointSnapshot p : e.getValue()) b.dataPoint(p);
                out.add(b.build());
            }
            return out;
        }
    }

    public AltibaseCollector(Connection conn, Set<String> disabledMetrics, String exporterVersion) {
        this.conn = conn;
        this.disabledMetrics = Set.copyOf(disabledMetrics != null ? disabledMetrics : Set.of());
        this.exporterVersion = exporterVersion != null ? exporterVersion : "0.0.0";
    }

    @Override
    public MetricSnapshots collect() {
        long start = System.nanoTime();
        ScrapeContext ctx = null;
        int success = 0;
        try (Statement stmt = conn.createStatement()) {
            ctx = new ScrapeContext(stmt);
            scrape(ctx);
            success = 1;
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage(), e);
        }
        if (ctx == null) {
            ctx = new ScrapeContext();
        }
        double duration = (System.nanoTime() - start) / 1e9;
        ctx.addGauge("exporter_last_scrape_success", success);
        ctx.addGauge("scrape_duration_seconds", duration);
        if (success == 1) {
            log.info("Scrape completed: duration_seconds={} version={}", String.format("%.3f", duration), lastVersion);
        }
        List<MetricSnapshot> snapshots = new ArrayList<>(ctx.buildSnapshots());
        snapshots.add(InfoSnapshot.builder()
                .name("altibase_exporter_build")
                .help(AltibaseMetricDefs.help("exporter_build_info"))
                .dataPoint(new InfoSnapshot.InfoDataPointSnapshot(Labels.of("version", exporterVersion)))
                .build());
        snapshots.add(InfoSnapshot.builder()
                .name("altibase_version")
                .help(AltibaseMetricDefs.help("version"))
                .dataPoint(new InfoSnapshot.InfoDataPointSnapshot(Labels.of("version", lastVersion)))
                .build());
        return new MetricSnapshots(snapshots);
    }

    @Override
    public List<String> getPrometheusNames() {
        return AltibaseMetricDefs.allKeys().stream().map(AltibaseMetricDefs::name).toList();
    }

    boolean isDisabled(String key) {
        return !disabledMetrics.isEmpty() && disabledMetrics.contains(key);
    }

    private boolean shouldSkipScrape(ScrapeMetric a) {
        if (a == null || a.value().length == 0) return false;
        for (String key : a.value()) {
            if (!isDisabled(key)) return false;
        }
        return true;
    }

    private static final List<Method> SCRAPE_METHODS = discoverScrapeMethods();

    private static List<Method> discoverScrapeMethods() {
        List<Method> list = new ArrayList<>();
        for (Method m : AltibaseCollector.class.getDeclaredMethods()) {
            if (m.getAnnotation(ScrapeMetric.class) == null) continue;
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != ScrapeContext.class) continue;
            list.add(m);
        }
        list.sort(Comparator.comparing(Method::getName));
        return Collections.unmodifiableList(list);
    }

    private void scrape(ScrapeContext ctx) throws SQLException {
        for (Method method : SCRAPE_METHODS) {
            ScrapeMetric a = method.getAnnotation(ScrapeMetric.class);
            if (shouldSkipScrape(a)) continue;
            try {
                method.setAccessible(true);
                method.invoke(this, ctx);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot invoke " + method.getName(), e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (a.catchSchemaError() && cause instanceof SQLException) {
                    log.warn("{} scrape skipped (schema may differ across Altibase versions): {}", method.getName(), cause.getMessage());
                } else {
                    throw cause != null ? (SQLException) cause : new SQLException(e);
                }
            }
        }
    }

    @ScrapeMetric("instance_working_time_seconds")
    private void scrapeInstanceWorkingTime(ScrapeContext ctx) throws SQLException {
        long workingTime = queryLong(ctx, "SELECT WORKING_TIME_SEC FROM V$INSTANCE");
        ctx.addGauge("instance_working_time_seconds", workingTime);
    }

    @ScrapeMetric(value = "transaction_manager_count", catchSchemaError = true)
    private void scrapeTransactionManagerCount(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT TOTAL_COUNT, ACTIVE_COUNT FROM V$TRANSACTION_MGR")) {
            if (rs.next()) {
                long total = rs.getLong(1);
                long active = rs.getLong(2);
                ctx.addGauge("transaction_manager_count", Labels.of("status", "total"), total);
                ctx.addGauge("transaction_manager_count", Labels.of("status", "active"), active);
            }
        }
    }

    /** Trigger count from RDBMS catalog (SYSTEM_.SYS_TRIGGERS_): total and per schema. */
    @ScrapeMetric(value = "trigger_count", catchSchemaError = true)
    private void scrapeTriggerCount(ScrapeContext ctx) throws SQLException {
        long total = queryLong(ctx, "SELECT COUNT(*) FROM SYSTEM_.SYS_TRIGGERS_");
        ctx.addGauge("trigger_count", Labels.of("schema", "total"), total);
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT U.USER_NAME, COUNT(*) FROM SYSTEM_.SYS_TRIGGERS_ T, SYSTEM_.SYS_USERS_ U WHERE T.USER_ID = U.USER_ID GROUP BY U.USER_NAME")) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1));
                long count = rs.getLong(2);
                if (!schema.isEmpty())
                    ctx.addGauge("trigger_count", Labels.of("schema", schema), count);
            }
        }
    }

    @ScrapeMetric("sessions")
    private void scrapeSessions(ScrapeContext ctx) throws SQLException {
        long totalSessions = queryLong(ctx, "SELECT COUNT(*) FROM V$SESSION");
        long activeSessions = queryLong(ctx, "SELECT COUNT(*) FROM V$SESSION WHERE ACTIVE_FLAG = 1");
        ctx.addGauge("sessions", Labels.of("status", "total"), totalSessions);
        ctx.addGauge("sessions", Labels.of("status", "active"), activeSessions);
    }

    @ScrapeMetric("statements")
    private void scrapeStatements(ScrapeContext ctx) throws SQLException {
        long totalStatements = queryLong(ctx, "SELECT COUNT(*) FROM V$STATEMENT");
        long activeStatements = queryLong(ctx, "SELECT COUNT(*) FROM V$STATEMENT WHERE EXECUTE_FLAG = 1");
        ctx.addGauge("statements", Labels.of("status", "total"), totalStatements);
        ctx.addGauge("statements", Labels.of("status", "active"), activeStatements);
    }

    @ScrapeMetric(value = "sessions_by_user", catchSchemaError = true)
    private void scrapeSessionsByUser(ScrapeContext ctx) throws SQLException {
        // V$SESSION: DB_USERNAME, DB_USERID (Altibase 7.x)
        String sql = """
            SELECT COALESCE(S.DB_USERNAME, 'UNKNOWN'), COUNT(*), SUM(CASE WHEN S.ACTIVE_FLAG = 1 THEN 1 ELSE 0 END) \
            FROM V$SESSION S GROUP BY COALESCE(S.DB_USERNAME, 'UNKNOWN')
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String userName = nullToEmpty(rs.getString(1));
                long total = rs.getLong(2);
                long active = rs.getLong(3);
                ctx.addGauge("sessions_by_user", Labels.of("user_name", userName, "status", "total"), total);
                ctx.addGauge("sessions_by_user", Labels.of("user_name", userName, "status", "active"), active);
            }
        }
    }

    @ScrapeMetric(value = "statements_by_user", catchSchemaError = true)
    private void scrapeStatementsByUser(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT COALESCE(S.DB_USERNAME, 'UNKNOWN'), COUNT(*), SUM(CASE WHEN ST.EXECUTE_FLAG = 1 THEN 1 ELSE 0 END) \
            FROM V$STATEMENT ST JOIN V$SESSION S ON ST.SESSION_ID = S.ID \
            GROUP BY COALESCE(S.DB_USERNAME, 'UNKNOWN')
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String userName = nullToEmpty(rs.getString(1));
                long total = rs.getLong(2);
                long active = rs.getLong(3);
                ctx.addGauge("statements_by_user", Labels.of("user_name", userName, "status", "total"), total);
                ctx.addGauge("statements_by_user", Labels.of("user_name", userName, "status", "active"), active);
            }
        }
    }

    @ScrapeMetric({"memstat_max_total_bytes", "memstat_alloc_bytes"})
    private void scrapeMemstatTotals(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT SUM(MAX_TOTAL_SIZE), SUM(ALLOC_SIZE) FROM V$MEMSTAT")) {
            if (rs.next()) {
                if (!isDisabled("memstat_max_total_bytes")) ctx.addGauge("memstat_max_total_bytes", rs.getLong(1));
                if (!isDisabled("memstat_alloc_bytes")) ctx.addGauge("memstat_alloc_bytes", rs.getLong(2));
            }
        }
    }

    @ScrapeMetric({"buffer_pool_hit_ratio", "buffer_pool_victim_fails"})
    private void scrapeBufferPool(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT HIT_RATIO, VICTIM_FAILS FROM V$BUFFPOOL_STAT")) {
            if (rs.next()) {
                if (!isDisabled("buffer_pool_hit_ratio")) ctx.addGauge("buffer_pool_hit_ratio", rs.getDouble(1));
                if (!isDisabled("buffer_pool_victim_fails")) ctx.addGauge("buffer_pool_victim_fails", rs.getDouble(2));
            }
        }
    }

    @ScrapeMetric({"logfile_oldest", "logfile_current", "logfile_gap"})
    private void scrapeLogfile(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT OLDEST_ACTIVE_LOGFILE, CURRENT_LOGFILE, (CURRENT_LOGFILE - OLDEST_ACTIVE_LOGFILE) FROM V$ARCHIVE")) {
            if (rs.next()) {
                if (!isDisabled("logfile_oldest")) ctx.addGauge("logfile_oldest", rs.getLong(1));
                if (!isDisabled("logfile_current")) ctx.addGauge("logfile_current", rs.getLong(2));
                if (!isDisabled("logfile_gap")) ctx.addGauge("logfile_gap", rs.getLong(3));
            }
        }
    }

    @ScrapeMetric("lf_prepare_wait_count")
    private void scrapeLfPrepareWait(ScrapeContext ctx) throws SQLException {
        long lfPrepareWait = queryLong(ctx, "SELECT LF_PREPARE_WAIT_COUNT FROM V$LFG");
        ctx.addGauge("lf_prepare_wait_count", lfPrepareWait);
    }

    @ScrapeMetric("replication_sender_count")
    private void scrapeReplicationSenderCount(ScrapeContext ctx) throws SQLException {
        long repSender = queryLong(ctx, "SELECT COUNT(*) FROM V$REPSENDER");
        ctx.addGauge("replication_sender_count", repSender);
    }

    @ScrapeMetric("replication_receiver_count")
    private void scrapeReplicationReceiverCount(ScrapeContext ctx) throws SQLException {
        long repReceiver = queryLong(ctx, "SELECT COUNT(*) FROM V$REPRECEIVER");
        ctx.addGauge("replication_receiver_count", repReceiver);
    }

    @ScrapeMetric(value = {"replication_peer", "replication_sender_xsn", "replication_sender_commit_xsn", "replication_sender_net_error_flag"})
    private void scrapeReplicationPeer(ScrapeContext ctx) throws SQLException {
        scrapeReplicationSender(ctx);
        scrapeReplicationReceiver(ctx);
    }

    /** One query to V$REPSENDER: peer + XSN/COMMIT_XSN/NET_ERROR_FLAG when columns exist; fallback to peer-only. */
    private void scrapeReplicationSender(ScrapeContext ctx) throws SQLException {
        String fullPeer = "SELECT REP_NAME, PEER_IP, PEER_PORT, STATUS, REPL_MODE, XSN, COMMIT_XSN, NET_ERROR_FLAG FROM V$REPSENDER";
        String fullRemote = "SELECT REP_NAME, REMOTE_IP, REMOTE_REP_PORT, STATUS, REPL_MODE, XSN, COMMIT_XSN, NET_ERROR_FLAG FROM V$REPSENDER";
        try (ResultSet rs = ctx.statement().executeQuery(fullPeer)) {
            while (rs.next()) addReplicationSenderRowWithDetail(ctx, rs);
            return;
        } catch (SQLException e) {
            if (!isColumnNotFound(e)) throw e;
        }
        try (ResultSet rs = ctx.statement().executeQuery(fullRemote)) {
            while (rs.next()) addReplicationSenderRowWithDetail(ctx, rs);
            return;
        } catch (SQLException e) {
            if (!isColumnNotFound(e)) throw e;
        }
        String peerOnly = "SELECT REP_NAME, PEER_IP, PEER_PORT, STATUS, REPL_MODE FROM V$REPSENDER";
        String remoteOnly = "SELECT REP_NAME, REMOTE_IP, REMOTE_REP_PORT, STATUS, REPL_MODE FROM V$REPSENDER";
        String peerNoMode = "SELECT REP_NAME, PEER_IP, PEER_PORT, STATUS FROM V$REPSENDER";
        String remoteNoMode = "SELECT REP_NAME, REMOTE_IP, REMOTE_REP_PORT, STATUS FROM V$REPSENDER";
        try (ResultSet rs = ctx.statement().executeQuery(peerOnly)) {
            while (rs.next()) addReplicationPeerRow(ctx, rs, "sender", "master", true, true);
            return;
        } catch (SQLException e) {
            if (!isColumnNotFound(e)) throw e;
        }
        try (ResultSet rs = ctx.statement().executeQuery(remoteOnly)) {
            while (rs.next()) addReplicationPeerRow(ctx, rs, "sender", "master", true, true);
            return;
        } catch (SQLException e) {
            if (!isColumnNotFound(e)) throw e;
        }
        try (ResultSet rs = ctx.statement().executeQuery(peerNoMode)) {
            while (rs.next()) addReplicationPeerRow(ctx, rs, "sender", "master", true, false);
        } catch (SQLException e) {
            if (!isColumnNotFound(e)) throw e;
            try (ResultSet rs = ctx.statement().executeQuery(remoteNoMode)) {
                while (rs.next()) addReplicationPeerRow(ctx, rs, "sender", "master", true, false);
            }
        }
    }

    private void addReplicationSenderRowWithDetail(ScrapeContext ctx, ResultSet rs) throws SQLException {
        String repName = nullToEmpty(rs.getString(1)).trim();
        String peer = peerString(rs.getString(2), rs.getObject(3));
        String status = senderStatusLabel(rs.getInt(4));
        String mode = "unknown";
        String m = rs.getString(5);
        if (m != null && !(m = m.trim()).isEmpty()) mode = m.toLowerCase();
        if (!repName.isEmpty() || !peer.isEmpty())
            ctx.addGauge("replication_peer", Labels.of("replication", repName, "role", "sender", "instance_role", "master", "status", status, "mode", mode, "peer", peer), 1);
        Labels labels = Labels.of("replication", repName);
        ctx.addGauge("replication_sender_xsn", labels, rs.getLong(6));
        ctx.addGauge("replication_sender_commit_xsn", labels, rs.getLong(7));
        ctx.addGauge("replication_sender_net_error_flag", labels, rs.getLong(8));
    }

    private void scrapeReplicationReceiver(ScrapeContext ctx) throws SQLException {
        String receiverSqlRemote = "SELECT REP_NAME, REMOTE_IP, REMOTE_REP_PORT FROM V$REPRECEIVER";
        String receiverSqlPeer = "SELECT REP_NAME, PEER_IP, PEER_PORT FROM V$REPRECEIVER";
        try (ResultSet rs = ctx.statement().executeQuery(receiverSqlRemote)) {
            while (rs.next()) addReplicationPeerRow(ctx, rs, "receiver", "slave", false, false);
        } catch (SQLException e) {
            if (!isColumnNotFound(e)) throw e;
            try (ResultSet rs = ctx.statement().executeQuery(receiverSqlPeer)) {
                while (rs.next()) addReplicationPeerRow(ctx, rs, "receiver", "slave", false, false);
            }
        }
    }

    private void addReplicationPeerRow(ScrapeContext ctx, ResultSet rs, String role, String instanceRole, boolean hasStatus, boolean hasMode) throws SQLException {
        String repName = nullToEmpty(rs.getString(1)).trim();
        String peer = peerString(rs.getString(2), rs.getObject(3));
        String status = hasStatus ? senderStatusLabel(rs.getInt(4)) : "active";
        String mode = "unknown";
        if (hasMode && hasStatus) {
            String m = rs.getString(5);
            if (m != null && !(m = m.trim()).isEmpty()) mode = m.toLowerCase();
        }
        if (!repName.isEmpty() || !peer.isEmpty())
            ctx.addGauge("replication_peer", Labels.of("replication", repName, "role", role, "instance_role", instanceRole, "status", status, "mode", mode, "peer", peer), 1);
    }

    private static boolean isColumnNotFound(SQLException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("Column not found") || msg.contains("column not found"));
    }

    /** Maps V$REPSENDER.STATUS: 0=STOP, 1=RUN, 2=RETRY. */
    private static String senderStatusLabel(int status) {
        return switch (status) {
            case 0 -> "stopped";
            case 1 -> "active";
            case 2 -> "retry";
            default -> "unknown";
        };
    }

    private static String peerString(String host, Object port) {
        String h = host != null ? host.trim() : "";
        String p = port != null ? String.valueOf(port) : "";
        if (h.isEmpty() && p.isEmpty()) return "";
        if (p.isEmpty()) return h;
        return h + ":" + p;
    }

    @ScrapeMetric("memory_table_usage_bytes")
    private void scrapeMemoryTableUsage(ScrapeContext ctx) throws SQLException {
        long memTbl = queryLong(ctx, "SELECT SUM(FIXED_ALLOC_MEM) + SUM(VAR_ALLOC_MEM) FROM V$MEMTBL_INFO");
        ctx.addGauge("memory_table_usage_bytes", memTbl);
    }

    @ScrapeMetric("disk_table_usage_bytes")
    private void scrapeDiskTableUsage(ScrapeContext ctx) throws SQLException {
        long diskTbl = queryLong(ctx, "SELECT SUM(DISK_TOTAL_PAGE_CNT * 8192) FROM V$DISKTBL_INFO");
        ctx.addGauge("disk_table_usage_bytes", diskTbl);
    }

    @ScrapeMetric("version_info")
    private void scrapeVersion(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT PRODUCT_VERSION FROM V$VERSION")) {
            if (rs.next()) {
                String v = rs.getString(1);
                lastVersion = v != null && !v.isEmpty() ? v.trim() : "unknown";
            }
        }
    }

    @ScrapeMetric("archive_mode")
    private void scrapeArchiveMode(ScrapeContext ctx) throws SQLException {
        long mode = queryLong(ctx, "SELECT ARCHIVE_MODE FROM V$ARCHIVE");
        ctx.addGauge("archive_mode", mode);
    }

    @ScrapeMetric("memstat_usage_ratio")
    private void scrapeMemstatUsageRatio(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT A.NAME, A.MAX_TOTAL_SIZE / B.TOTAL_USAGE AS USAGE_PERCENTAGE FROM V$MEMSTAT A, \
            (SELECT SUM(MAX_TOTAL_SIZE) AS TOTAL_USAGE FROM V$MEMSTAT) B \
            ORDER BY USAGE_PERCENTAGE DESC LIMIT 10
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null) name = name.trim();
                double pct = rs.getDouble(2);
                if (name != null && !Double.isNaN(pct)) ctx.addGauge("memstat_usage_ratio", Labels.of("name", name), pct);
            }
        }
    }

    @ScrapeMetric("gc_gap")
    private void scrapeGcGap(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT GC_NAME, ADD_OID_CNT - GC_OID_CNT AS GC_GAP FROM V$MEMGC")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long gap = rs.getLong(2);
                if (name != null) ctx.addGauge("gc_gap", Labels.of("gc_name", name), gap);
            }
        }
    }

    @ScrapeMetric("tablespace_total_bytes")
    private void scrapeTablespaceTotalBytes(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT NAME, TOTAL_PAGE_COUNT * PAGE_SIZE AS TOTAL FROM V$TABLESPACES T, V$MEM_TABLESPACES M WHERE T.ID = M.SPACE_ID")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long total = rs.getLong(2);
                if (name != null) ctx.addGauge("tablespace_total_bytes", Labels.of("tbs_name", name), total);
            }
        }
    }

    @ScrapeMetric("tablespace_state")
    private void scrapeTablespaceState(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT NAME, DECODE(STATE, 1, 0, 2, 1, 0) AS ONLINE FROM V$TABLESPACES")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long online = rs.getLong(2);
                if (name != null) ctx.addGauge("tablespace_state", Labels.of("tbs_name", name, "state", online == 1 ? "ONLINE" : "OFFLINE"), online);
            }
        }
    }

    @ScrapeMetric("tablespace_usage_ratio")
    private void scrapeTablespaceUsageRatio(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT T.NAME, (M.ALLOC_PAGE_COUNT - M.FREE_PAGE_COUNT) * T.PAGE_SIZE * 1.0 / NULLIF(T.TOTAL_PAGE_COUNT * T.PAGE_SIZE, 0) AS USAGE \
            FROM V$TABLESPACES T, V$MEM_TABLESPACES M WHERE T.ID = M.SPACE_ID
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                double usage = rs.getDouble(2);
                if (name != null && !Double.isNaN(usage)) ctx.addGauge("tablespace_usage_ratio", Labels.of("tbs_name", name), usage);
            }
        }
    }

    @ScrapeMetric("file_io_reads")
    private void scrapeFileIoReads(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT B.NAME, A.PHYRDS FROM V$FILESTAT A, V$DATAFILES B WHERE A.SPACEID = B.SPACEID AND A.FILEID = B.ID AND A.PHYRDS > 0 ORDER BY A.PHYRDS DESC LIMIT 10";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                long val = rs.getLong(2);
                if (name != null) ctx.addGauge("file_io_reads", Labels.of("file_name", name), val);
            }
        }
    }

    @ScrapeMetric("file_io_writes")
    private void scrapeFileIoWrites(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT B.NAME, A.PHYWRTS FROM V$FILESTAT A, V$DATAFILES B WHERE A.SPACEID = B.SPACEID AND A.FILEID = B.ID ORDER BY A.PHYWRTS DESC LIMIT 10";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                long val = rs.getLong(2);
                if (name != null) ctx.addGauge("file_io_writes", Labels.of("file_name", name), val);
            }
        }
    }

    @ScrapeMetric("file_io_wait_seconds")
    private void scrapeFileIoWaitSeconds(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT B.NAME, CASE WHEN A.SINGLEBLKRDS > 0 THEN A.SINGLEBLKRDTIM * 1.0 / A.SINGLEBLKRDS ELSE 0 END AS AVERAGE_WAIT FROM V$FILESTAT A, V$DATAFILES B WHERE A.SPACEID = B.SPACEID AND A.FILEID = B.ID AND A.SINGLEBLKRDS > 0";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                double wait = rs.getDouble(2);
                if (name != null && !Double.isNaN(wait)) ctx.addGauge("file_io_wait_seconds", Labels.of("file_name", name), wait / 1e6);
            }
        }
    }

    @ScrapeMetric("system_event_time_waited_seconds")
    private void scrapeSystemEvent(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT EVENT || '(' || WAIT_CLASS || ')' AS NAME, TIME_WAITED FROM V$SYSTEM_EVENT WHERE WAIT_CLASS != 'Idle' ORDER BY TIME_WAITED DESC LIMIT 10")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long timeWaited = rs.getLong(2);
                if (name != null) ctx.addGauge("system_event_time_waited_seconds", Labels.of("event", name), timeWaited / 1e6);
            }
        }
    }

    @ScrapeMetric("session_event_time_waited_seconds")
    private void scrapeSessionEvent(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT EVENT || '(' || WAIT_CLASS || ')' AS NAME, TIME_WAITED FROM V$SESSION_EVENT WHERE WAIT_CLASS != 'Idle' ORDER BY TIME_WAITED DESC LIMIT 10")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long timeWaited = rs.getLong(2);
                if (name != null) ctx.addGauge("session_event_time_waited_seconds", Labels.of("event", name), timeWaited / 1e6);
            }
        }
    }

    @ScrapeMetric("memory_table_usage_bytes_per_table")
    private void scrapeMemoryTableUsagePerTable(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT TABLE_NAME, (FIXED_ALLOC_MEM+VAR_ALLOC_MEM) AS ALLOC FROM SYSTEM_.SYS_TABLES_ A, V$MEMTBL_INFO B WHERE A.USER_ID != 1 AND A.TABLE_OID = B.TABLE_OID ORDER BY ALLOC DESC LIMIT 5")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long alloc = rs.getLong(2);
                if (name != null) ctx.addGauge("memory_table_usage_bytes_per_table", Labels.of("table_name", name), alloc);
            }
        }
    }

    @ScrapeMetric("disk_table_usage_bytes_per_table")
    private void scrapeDiskTableUsagePerTable(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT C.TABLE_NAME, B.DISK_TOTAL_PAGE_CNT * A.PAGE_SIZE AS ALLOC FROM V$TABLESPACES A, V$DISKTBL_INFO B, SYSTEM_.SYS_TABLES_ C WHERE A.ID = B.TABLESPACE_ID AND B.TABLE_OID = C.TABLE_OID ORDER BY ALLOC DESC LIMIT 5")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long alloc = rs.getLong(2);
                if (name != null) ctx.addGauge("disk_table_usage_bytes_per_table", Labels.of("table_name", name), alloc);
            }
        }
    }

    @ScrapeMetric(value = "table_size_bytes", catchSchemaError = true)
    private void scrapeTableSize(ScrapeContext ctx) throws SQLException {
        // exclude system tables (USER_ID=1)
        String memSql = """
            SELECT U.USER_NAME, T.TABLE_NAME, TS.NAME AS TBS_NAME, (B.FIXED_ALLOC_MEM + B.VAR_ALLOC_MEM) AS SZ \
            FROM SYSTEM_.SYS_USERS_ U, SYSTEM_.SYS_TABLES_ T, V$MEMTBL_INFO B, V$TABLESPACES TS \
            WHERE T.USER_ID = U.USER_ID AND T.TABLE_OID = B.TABLE_OID AND T.TBS_ID = TS.ID AND T.USER_ID != 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(memSql)) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1));
                String tableName = nullToEmpty(rs.getString(2));
                String tablespace = nullToEmpty(rs.getString(3));
                long sizeBytes = rs.getLong(4);
                if (!tableName.isEmpty())
                    ctx.addGauge("table_size_bytes", Labels.of("schema", schema, "table_name", tableName, "tablespace", tablespace, "type", "memory"), sizeBytes);
            }
        }
        String diskSql = """
            SELECT U.USER_NAME, C.TABLE_NAME, A.NAME AS TBS_NAME, B.DISK_TOTAL_PAGE_CNT * A.PAGE_SIZE AS SZ \
            FROM V$TABLESPACES A, V$DISKTBL_INFO B, SYSTEM_.SYS_TABLES_ C, SYSTEM_.SYS_USERS_ U \
            WHERE A.ID = B.TABLESPACE_ID AND B.TABLE_OID = C.TABLE_OID AND C.USER_ID = U.USER_ID AND C.USER_ID != 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(diskSql)) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1));
                String tableName = nullToEmpty(rs.getString(2));
                String tablespace = nullToEmpty(rs.getString(3));
                long sizeBytes = rs.getLong(4);
                if (!tableName.isEmpty())
                    ctx.addGauge("table_size_bytes", Labels.of("schema", schema, "table_name", tableName, "tablespace", tablespace, "type", "disk"), sizeBytes);
            }
        }
    }

    @ScrapeMetric("queue_usage_bytes")
    private void scrapeQueueUsage(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT B.TABLE_NAME, SUM(C.FIXED_ALLOC_MEM+C.VAR_ALLOC_MEM) AS ALLOC FROM SYSTEM_.SYS_USERS_ A, SYSTEM_.SYS_TABLES_ B, V$MEMTBL_INFO C, V$TABLESPACES D \
            WHERE A.USER_NAME <> 'SYSTEM_' AND B.TABLE_TYPE = 'Q' AND A.USER_ID = B.USER_ID AND B.TABLE_OID = C.TABLE_OID AND B.TBS_ID = D.ID \
            GROUP BY B.TABLE_NAME
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                long alloc = rs.getLong(2);
                if (name != null) ctx.addGauge("queue_usage_bytes", Labels.of("table_name", name), alloc);
            }
        }
    }

    @ScrapeMetric(value = "segment_usage_bytes", catchSchemaError = true)
    private void scrapeSegmentUsage(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT A.NAME, SUM(B.EXTENT_TOTAL_COUNT*A.EXTENT_PAGE_COUNT*A.PAGE_SIZE) AS USAGE FROM V$TABLESPACES A, V$SEGMENT B WHERE A.ID = B.SPACE_ID GROUP BY A.NAME")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long usage = rs.getLong(2);
                if (name != null) ctx.addGauge("segment_usage_bytes", Labels.of("name", name), usage);
            }
        }
    }

    @ScrapeMetric(value = "index_alloc_size_bytes", catchSchemaError = true)
    private void scrapeIndexAllocSize(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT C.USER_NAME, F.TABLE_NAME, D.NAME AS TBS_NAME, E.INDEX_NAME, DECODE(E.INDEX_TYPE, 1, 'B-TREE', 'R-TREE') AS INDEX_TYPE, \
            D.EXTENT_PAGE_COUNT * D.PAGE_SIZE * A.EXTENT_TOTAL_COUNT AS ALLOC_BYTES \
            FROM V$SEGMENT A, V$INDEX B, V$TABLESPACES D, SYSTEM_.SYS_USERS_ C, SYSTEM_.SYS_INDICES_ E, SYSTEM_.SYS_TABLES_ F \
            WHERE A.SEGMENT_PID = B.INDEX_SEG_PID AND B.INDEX_ID = E.INDEX_ID AND E.USER_ID = C.USER_ID AND A.SPACE_ID = D.ID AND F.TBS_ID = D.ID \
            AND A.SEGMENT_TYPE = 'INDEX' AND F.USER_ID = E.USER_ID AND F.TABLE_OID = B.TABLE_OID
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1));
                String tableName = nullToEmpty(rs.getString(2));
                String tbsName = nullToEmpty(rs.getString(3));
                String indexName = nullToEmpty(rs.getString(4));
                String indexType = nullToEmpty(rs.getString(5));
                long allocBytes = rs.getLong(6);
                if (!isDisabled("index_alloc_size_bytes"))
                    ctx.addGauge("index_alloc_size_bytes", Labels.of("schema", schema, "table_name", tableName, "tablespace", tbsName, "index_name", indexName, "index_type", indexType), allocBytes);
            }
        }
    }

    @ScrapeMetric(value = "index_metadata", catchSchemaError = true)
    private void scrapeIndexMetadata(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT A.USER_NAME, B.TABLE_NAME, C.INDEX_NAME, C.INDEX_ID, NVL(D.NAME, 'SYS_TBS_MEMORY') AS TBS_NAME, C.IS_UNIQUE, C.COLUMN_CNT \
            FROM SYSTEM_.SYS_USERS_ A, SYSTEM_.SYS_TABLES_ B, SYSTEM_.SYS_INDICES_ C LEFT OUTER JOIN V$TABLESPACES D ON C.TBS_ID = D.ID \
            WHERE A.USER_NAME <> 'SYSTEM_' AND B.TABLE_TYPE = 'T' AND C.TABLE_ID = B.TABLE_ID AND C.USER_ID = A.USER_ID \
            ORDER BY B.TABLE_NAME, C.INDEX_NAME
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1));
                String tableName = nullToEmpty(rs.getString(2));
                String indexName = nullToEmpty(rs.getString(3));
                String indexId = getColumnAsString(rs, 4);
                String tbsName = nullToEmpty(rs.getString(5));
                String isUnique = getColumnAsString(rs, 6);
                String columnCnt = getColumnAsString(rs, 7);
                if (!isDisabled("index_metadata"))
                    ctx.addGauge("index_metadata", Labels.of("schema", schema, "table_name", tableName, "index_name", indexName, "index_id", indexId, "tablespace", tbsName, "is_unique", isUnique, "column_cnt", columnCnt), 1);
            }
        }
    }

    /** Reads a column as string so both numeric and char (e.g. IS_UNIQUE 'T'/'F') work across Altibase versions. */
    private static String getColumnAsString(ResultSet rs, int columnIndex) throws SQLException {
        Object o = rs.getObject(columnIndex);
        return o != null ? o.toString().trim() : "";
    }

    @ScrapeMetric(value = "index_information_mem", catchSchemaError = true)
    private void scrapeIndexInformationMem(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT C.USER_NAME, DECODE(F.TABLE_TYPE, 'Q', 'QUEUE', 'T', 'TABLE') AS OBJECT_TYPE, F.TABLE_NAME AS OBJECT_NAME, D.SPACE_NAME AS TABLESPACE_NAME, E.INDEX_NAME, DECODE(E.INDEX_TYPE, 1, 'B-TREE', 'R-TREE') AS INDEX_TYPE \
            FROM V$INDEX B, SYSTEM_.SYS_USERS_ C, V$MEM_TABLESPACES D, SYSTEM_.SYS_INDICES_ E, SYSTEM_.SYS_TABLES_ F \
            WHERE B.INDEX_ID = E.INDEX_ID AND E.USER_ID = C.USER_ID AND F.USER_ID = E.USER_ID AND F.TBS_ID = D.SPACE_ID AND F.TABLE_OID = B.TABLE_OID AND C.USER_NAME <> 'SYSTEM_'
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1));
                String objectType = nullToEmpty(rs.getString(2));
                String objectName = nullToEmpty(rs.getString(3));
                String tablespace = nullToEmpty(rs.getString(4));
                String indexName = nullToEmpty(rs.getString(5));
                String indexType = nullToEmpty(rs.getString(6));
                if (!isDisabled("index_information_mem"))
                    ctx.addGauge("index_information_mem", Labels.of("schema", schema, "object_type", objectType, "object_name", objectName, "tablespace", tablespace, "index_name", indexName, "index_type", indexType), 1);
            }
        }
    }

    @ScrapeMetric("lock_hold_detail")
    private void scrapeLockHoldInfo(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT STMT.SESSION_ID, STMT.TX_ID, L.IS_GRANT, L.LOCK_DESC, TBL.TABLE_NAME, STMT.TOTAL_TIME, SUBSTR(STMT.QUERY, 1, 50) \
            FROM SYSTEM_.SYS_TABLES_ TBL, V$STATEMENT STMT, V$LOCK L, V$LOCK_WAIT LOCK_WAIT \
            WHERE L.TRANS_ID = LOCK_WAIT.WAIT_FOR_TRANS_ID AND L.TABLE_OID = TBL.TABLE_OID AND L.TRANS_ID = STMT.TX_ID ORDER BY STMT.TOTAL_TIME DESC LIMIT 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            if (rs.next()) {
                long sessionId = rs.getLong(1);
                long txId = rs.getLong(2);
                long isGrant = rs.getLong(3);
                String lockDesc = nullToEmpty(rs.getString(4));
                String tableName = nullToEmpty(rs.getString(5));
                long totalTimeUs = rs.getLong(6);
                String query = nullToEmpty(rs.getString(7));
                ctx.addGauge("lock_hold_detail", Labels.of("session_id", String.valueOf(sessionId), "tx_id", String.valueOf(txId), "table_name", tableName, "total_time_seconds", String.valueOf(totalTimeUs / 1e6), "query", query, "is_grant", String.valueOf(isGrant), "lock_desc", lockDesc), 1);
            } else {
                ctx.addGauge("lock_hold_detail", Labels.of("session_id", "0", "tx_id", "0", "table_name", "", "total_time_seconds", "0", "query", "", "is_grant", "0", "lock_desc", ""), 0);
            }
        }
    }

    @ScrapeMetric("lock_wait_detail")
    private void scrapeLockWaitInfo(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT STMT.SESSION_ID, STMT.TX_ID, L.IS_GRANT, NVL(LOCK_WAIT.WAIT_FOR_TRANS_ID, -1), L.LOCK_DESC, TBL.TABLE_NAME, STMT.TOTAL_TIME, SUBSTR(STMT.QUERY, 1, 50) \
            FROM SYSTEM_.SYS_TABLES_ TBL, V$STATEMENT STMT, V$LOCK L, V$LOCK_WAIT LOCK_WAIT \
            WHERE L.TRANS_ID = LOCK_WAIT.TRANS_ID AND L.TABLE_OID = TBL.TABLE_OID AND L.TRANS_ID = STMT.TX_ID ORDER BY STMT.TOTAL_TIME DESC LIMIT 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            if (rs.next()) {
                long sessionId = rs.getLong(1);
                long txId = rs.getLong(2);
                long isGrant = rs.getLong(3);
                long waitForTxId = rs.getLong(4);
                String lockDesc = nullToEmpty(rs.getString(5));
                String tableName = nullToEmpty(rs.getString(6));
                long totalTimeUs = rs.getLong(7);
                String query = nullToEmpty(rs.getString(8));
                ctx.addGauge("lock_wait_detail", Labels.of("session_id", String.valueOf(sessionId), "tx_id", String.valueOf(txId), "wait_for_tx_id", String.valueOf(waitForTxId), "table_name", tableName, "total_time_seconds", String.valueOf(totalTimeUs / 1e6), "query", query, "is_grant", String.valueOf(isGrant), "lock_desc", lockDesc), 1);
            } else {
                ctx.addGauge("lock_wait_detail", Labels.of("session_id", "0", "tx_id", "0", "wait_for_tx_id", "0", "table_name", "", "total_time_seconds", "0", "query", "", "is_grant", "0", "lock_desc", ""), 0);
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @ScrapeMetric(value = "tx_of_memory_view_scn", catchSchemaError = true)
    private void scrapeTxOfMemoryViewScn(ScrapeContext ctx) throws SQLException {
        String fullSql = """
            SELECT ST.SESSION_ID, TX.ID AS TX_ID, ST.TOTAL_TIME, ST.EXECUTE_TIME, SUBSTR(ST.QUERY, 1, 50) FROM V$STATEMENT ST, V$TRANSACTION TX \
            WHERE ST.TX_ID = TX.ID AND TX.ID IN (SELECT T.ID FROM V$TRANSACTION T, (SELECT MINMEMSCNINTXS AS SCN_VAL FROM V$MEMGC LIMIT 1) GC \
            WHERE T.MEMORY_VIEW_SCN = GC.SCN_VAL OR T.MIN_MEMORY_LOB_VIEW_SCN = GC.SCN_VAL) AND ST.SESSION_ID != SESSION_ID() AND TX.SESSION_ID <> SESSION_ID() ORDER BY ST.TOTAL_TIME DESC LIMIT 1
            """;
        String fallbackSql = """
            SELECT ST.SESSION_ID, TX.ID AS TX_ID, ST.TOTAL_TIME, ST.EXECUTE_TIME, SUBSTR(ST.QUERY, 1, 50) FROM V$STATEMENT ST, V$TRANSACTION TX \
            WHERE ST.TX_ID = TX.ID AND ST.SESSION_ID != SESSION_ID() AND TX.SESSION_ID <> SESSION_ID() ORDER BY ST.TOTAL_TIME DESC LIMIT 1
            """;
        String sql = fullSql;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (ResultSet rs = ctx.statement().executeQuery(sql)) {
                if (rs.next()) {
                    long sessionId = rs.getLong(1);
                    long txId = rs.getLong(2);
                    long totalTimeUs = rs.getLong(3);
                    long executeTimeUs = rs.getLong(4);
                    String query = nullToEmpty(rs.getString(5));
                    ctx.addGauge("tx_of_memory_view_scn", Labels.of("session_id", String.valueOf(sessionId), "tx_id", String.valueOf(txId), "total_time_seconds", String.valueOf(totalTimeUs / 1e6), "execute_time_seconds", String.valueOf(executeTimeUs / 1e6), "query", query), 1);
                } else {
                    ctx.addGauge("tx_of_memory_view_scn", Labels.of("session_id", "0", "tx_id", "0", "total_time_seconds", "0", "execute_time_seconds", "0", "query", "none"), 0);
                }
                return;
            } catch (SQLException e) {
                if (attempt == 0 && e.getMessage() != null && e.getMessage().contains("Column not found")) {
                    log.debug("tx_of_memory_view_scn: full query not supported (V$ schema may differ across Altibase versions), using fallback");
                    sql = fallbackSql;
                } else {
                    throw e;
                }
            }
        }
    }

    @ScrapeMetric("long_run_query_detail")
    private void scrapeLongRunQueryInfo(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT SESSION_ID, ID, TX_ID, (PARSE_TIME+VALIDATE_TIME+OPTIMIZE_TIME) AS PREPARE_TIME, FETCH_TIME, EXECUTE_TIME, TOTAL_TIME, NVL(LTRIM(QUERY), 'NONE') \
            FROM V$STATEMENT WHERE EXECUTE_FLAG = 1 AND EXECUTE_TIME/1000000 > 1 ORDER BY EXECUTE_TIME DESC LIMIT 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            if (rs.next()) {
                long sessionId = rs.getLong(1);
                long stmtId = rs.getLong(2);
                long txId = rs.getLong(3);
                long prepareTimeUs = rs.getLong(4);
                long fetchTimeUs = rs.getLong(5);
                long executeTimeUs = rs.getLong(6);
                long totalTimeUs = rs.getLong(7);
                String query = nullToEmpty(rs.getString(8));
                ctx.addGauge("long_run_query_detail", Labels.of("session_id", String.valueOf(sessionId), "stmt_id", String.valueOf(stmtId), "tx_id", String.valueOf(txId), "prepare_time_seconds", String.valueOf(prepareTimeUs / 1e6), "fetch_time_seconds", String.valueOf(fetchTimeUs / 1e6), "execute_time_seconds", String.valueOf(executeTimeUs / 1e6), "total_time_seconds", String.valueOf(totalTimeUs / 1e6), "query", query), 1);
            } else {
                ctx.addGauge("long_run_query_detail", Labels.of("session_id", "0", "stmt_id", "0", "tx_id", "0", "prepare_time_seconds", "0", "fetch_time_seconds", "0", "execute_time_seconds", "0", "total_time_seconds", "0", "query", "none"), 0);
            }
        }
    }

    @ScrapeMetric("utrans_query_detail")
    private void scrapeUtransQueryInfo(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT ST.SESSION_ID, SS.COMM_NAME, SS.CLIENT_PID, SS.CLIENT_APP_INFO, (BASE_TIME - TR.FIRST_UPDATE_TIME) AS UTRANS_TIME, ST.EXECUTE_TIME, ST.TOTAL_TIME, NVL(LTRIM(ST.QUERY), 'NONE') \
            FROM V$TRANSACTION TR, V$STATEMENT ST, V$SESSIONMGR, V$SESSION SS WHERE TR.ID = ST.TX_ID AND ST.SESSION_ID = SS.ID AND TR.FIRST_UPDATE_TIME != 0 AND (BASE_TIME - TR.FIRST_UPDATE_TIME) > 1 ORDER BY (BASE_TIME - TR.FIRST_UPDATE_TIME) DESC LIMIT 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            if (rs.next()) {
                long sessionId = rs.getLong(1);
                String clientIp = nullToEmpty(rs.getString(2));
                long clientPid = rs.getLong(3);
                String clientApp = nullToEmpty(rs.getString(4));
                long utransTimeSec = rs.getLong(5);
                long executeTimeUs = rs.getLong(6);
                long totalTimeUs = rs.getLong(7);
                String query = nullToEmpty(rs.getString(8));
                ctx.addGauge("utrans_query_detail", Labels.of("session_id", String.valueOf(sessionId), "client_ip", clientIp, "client_pid", String.valueOf(clientPid), "client_app_info", clientApp, "utrans_time_seconds", String.valueOf(utransTimeSec), "execute_time_seconds", String.valueOf(executeTimeUs / 1e6), "total_time_seconds", String.valueOf(totalTimeUs / 1e6), "query", query), 1);
            } else {
                ctx.addGauge("utrans_query_detail", Labels.of("session_id", "0", "client_ip", "", "client_pid", "0", "client_app_info", "", "utrans_time_seconds", "0", "execute_time_seconds", "0", "total_time_seconds", "0", "query", "none"), 0);
            }
        }
    }

    @ScrapeMetric(value = "fullscan_query_detail", catchSchemaError = true)
    private void scrapeFullscanQueryInfo(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT T.SESSION_ID, S.COMM_NAME, S.CLIENT_PID, S.CLIENT_APP_INFO, (T.PARSE_TIME+T.VALIDATE_TIME+T.OPTIMIZE_TIME) AS PREPARE_TIME, T.FETCH_TIME, T.EXECUTE_TIME, T.TOTAL_TIME, NVL(LTRIM(T.QUERY), 'NONE') \
            FROM V$STATEMENT T, V$SESSION S WHERE S.ID = T.SESSION_ID AND (T.MEM_CURSOR_FULL_SCAN > 0 OR T.DISK_CURSOR_FULL_SCAN > 0) AND UPPER(T.QUERY) NOT LIKE '%INSERT%' AND S.CLIENT_INFO != 'altibase-exporter' ORDER BY T.EXECUTE_TIME DESC LIMIT 1
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            if (rs.next()) {
                long sessionId = rs.getLong(1);
                String clientIp = nullToEmpty(rs.getString(2));
                long clientPid = rs.getLong(3);
                String clientApp = nullToEmpty(rs.getString(4));
                long prepareTimeUs = rs.getLong(5);
                long fetchTimeUs = rs.getLong(6);
                long executeTimeUs = rs.getLong(7);
                long totalTimeUs = rs.getLong(8);
                String query = nullToEmpty(rs.getString(9));
                ctx.addGauge("fullscan_query_detail", Labels.of("session_id", String.valueOf(sessionId), "client_ip", clientIp, "client_pid", String.valueOf(clientPid), "client_app_info", clientApp, "prepare_time_seconds", String.valueOf(prepareTimeUs / 1e6), "fetch_time_seconds", String.valueOf(fetchTimeUs / 1e6), "execute_time_seconds", String.valueOf(executeTimeUs / 1e6), "total_time_seconds", String.valueOf(totalTimeUs / 1e6), "query", query), 1);
            } else {
                ctx.addGauge("fullscan_query_detail", Labels.of("session_id", "0", "client_ip", "", "client_pid", "0", "client_app_info", "", "prepare_time_seconds", "0", "fetch_time_seconds", "0", "execute_time_seconds", "0", "total_time_seconds", "0", "query", "none"), 0);
            }
        }
    }

    @ScrapeMetric("memstat_bytes")
    private void scrapeMemstatByName(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT NAME, MAX_TOTAL_SIZE, ALLOC_SIZE FROM V$MEMSTAT ORDER BY MAX_TOTAL_SIZE DESC LIMIT 10";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null) name = name.trim();
                long maxT = rs.getLong(2);
                long allocV = rs.getLong(3);
                if (name != null) {
                    ctx.addGauge("memstat_bytes", Labels.of("name", name, "type", "max_total_size"), maxT);
                    ctx.addGauge("memstat_bytes", Labels.of("name", name, "type", "alloc_size"), allocV);
                }
            }
        }
    }

    @ScrapeMetric({"lock_hold_count", "lock_wait_count"})
    private void scrapeLocks(ScrapeContext ctx) throws SQLException {
        long hold = 0, wait = 0;
        String sql = """
            SELECT DECODE(LOCK_STMT.STATE, 0, 'LOCK_HOLD_COUNT', 1, 'LOCK_WAIT_COUNT') AS LOCK_STATE, COUNT(*) AS CNT \
            FROM SYSTEM_.SYS_TABLES_ TBL, V$LOCK_STATEMENT LOCK_STMT, V$STATEMENT STMT \
            LEFT OUTER JOIN V$LOCK_WAIT LOCK_WAIT ON STMT.TX_ID = LOCK_WAIT.TRANS_ID \
            WHERE TBL.TABLE_OID = LOCK_STMT.TABLE_OID AND STMT.SESSION_ID = LOCK_STMT.SESSION_ID \
            AND STMT.TX_ID = LOCK_STMT.TX_ID AND LOCK_STMT.STATE IN (0,1) GROUP BY LOCK_STMT.STATE
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String state = rs.getString(1);
                long cnt = rs.getLong(2);
                if ("LOCK_HOLD_COUNT".equals(state)) hold = cnt;
                else if ("LOCK_WAIT_COUNT".equals(state)) wait = cnt;
            }
        }
        if (!isDisabled("lock_hold_count")) ctx.addGauge("lock_hold_count", hold);
        if (!isDisabled("lock_wait_count")) ctx.addGauge("lock_wait_count", wait);
    }

    @ScrapeMetric(value = "lock_table", catchSchemaError = true)
    private void scrapeLockTableList(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT A.TABLE_NAME, B.TRANS_ID, B.LOCK_DESC FROM SYSTEM_.SYS_TABLES_ A, V$LOCK B WHERE A.TABLE_OID = B.TABLE_OID";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String tableName = nullToEmpty(rs.getString(1));
                long transId = rs.getLong(2);
                String lockDesc = nullToEmpty(rs.getString(3));
                ctx.addGauge("lock_table", Labels.of("table_name", tableName, "trans_id", String.valueOf(transId), "lock_desc", lockDesc), 1);
            }
        }
    }

    @ScrapeMetric("long_run_query_count")
    private void scrapeLongRunQueryCount(ScrapeContext ctx) throws SQLException {
        long longRun = queryLong(ctx, "SELECT COUNT(*) FROM V$STATEMENT WHERE EXECUTE_FLAG = 1 AND EXECUTE_TIME/1000000 > 1");
        ctx.addGauge("long_run_query_count", longRun);
    }

    @ScrapeMetric("utrans_query_count")
    private void scrapeUtransQueryCount(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM V$TRANSACTION TR, V$STATEMENT ST, V$SESSIONMGR, V$SESSION SS \
            WHERE TR.ID = ST.TX_ID AND ST.SESSION_ID = SS.ID AND TR.FIRST_UPDATE_TIME != 0 AND (BASE_TIME - TR.FIRST_UPDATE_TIME) > 1
            """;
        long utrans = queryLong(ctx, sql);
        ctx.addGauge("utrans_query_count", utrans);
    }

    @ScrapeMetric("fullscan_query_count")
    private void scrapeFullscanQueryCount(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM V$STATEMENT T, V$SESSION S WHERE S.ID = T.SESSION_ID \
            AND (MEM_CURSOR_FULL_SCAN > 0 OR DISK_CURSOR_FULL_SCAN > 0) AND UPPER(QUERY) NOT LIKE '%INSERT%' \
            AND S.CLIENT_INFO != 'altibase-exporter'
            """;
        long fullscan = queryLong(ctx, sql);
        ctx.addGauge("fullscan_query_count", fullscan);
    }

    @ScrapeMetric(value = {"replication_gap", "replication_gap_size_bytes", "replication_gap_rep_last_sn", "replication_gap_rep_sn"}, catchSchemaError = true)
    private void scrapeReplicationGap(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT REP_NAME, REP_LAST_SN, REP_SN, REP_GAP, REP_GAP_SIZE FROM V$REPGAP";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name == null) continue;
                name = name.trim();
                Labels labels = Labels.of("replication", name);
                ctx.addGauge("replication_gap_rep_last_sn", labels, rs.getLong(2));
                ctx.addGauge("replication_gap_rep_sn", labels, rs.getLong(3));
                ctx.addGauge("replication_gap", labels, rs.getLong(4));
                ctx.addGauge("replication_gap_size_bytes", labels, rs.getLong(5));
            }
        }
    }

    @ScrapeMetric(value = {"job_state", "job_exec_count", "job_error_code", "job_interval"}, catchSchemaError = true)
    private void scrapeJobs(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT JOB_NAME, STATE, EXEC_COUNT, ERROR_CODE, INTERVAL FROM SYSTEM_.SYS_JOBS_";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String jobName = nullToEmpty(rs.getString(1)).trim();
                if (jobName.isEmpty()) continue;
                Labels labels = Labels.of("job_name", jobName);
                ctx.addGauge("job_state", labels, rs.getLong(2));
                ctx.addGauge("job_exec_count", labels, rs.getLong(3));
                ctx.addGauge("job_error_code", labels, rs.getLong(4));
                ctx.addGauge("job_interval", labels, rs.getLong(5));
            }
        }
    }

    @ScrapeMetric(value = "replication_receiver_apply_xsn", catchSchemaError = true)
    private void scrapeReplicationReceiverApplyXsn(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT TRIM(REP_NAME), APPLY_XSN FROM V$REPRECEIVER")) {
            while (rs.next()) {
                String name = nullToEmpty(rs.getString(1)).trim();
                if (!name.isEmpty()) ctx.addGauge("replication_receiver_apply_xsn", Labels.of("replication", name), rs.getLong(2));
            }
        }
    }

    @ScrapeMetric(value = {"tablespace_disk_curr_bytes", "tablespace_disk_max_bytes", "tablespace_disk_usage_ratio"}, catchSchemaError = true)
    private void scrapeTablespaceDisk(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT V.NAME, SUM(D.CURRSIZE), SUM(DECODE(D.MAXSIZE, 0, D.CURRSIZE, D.MAXSIZE)) FROM V$DATAFILES D, V$TABLESPACES V WHERE D.SPACEID = V.ID GROUP BY V.NAME";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = nullToEmpty(rs.getString(1)).trim();
                if (name.isEmpty()) continue;
                Labels labels = Labels.of("tbs_name", name);
                long curr = rs.getLong(2);
                long max = rs.getLong(3);
                ctx.addGauge("tablespace_disk_curr_bytes", labels, curr);
                ctx.addGauge("tablespace_disk_max_bytes", labels, max);
                double ratio = (max > 0) ? (curr * 1.0 / max) : 0;
                ctx.addGauge("tablespace_disk_usage_ratio", labels, ratio);
            }
        }
    }

    @ScrapeMetric(value = "replication_item", catchSchemaError = true)
    private void scrapeReplicationItems(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT REPLICATION_NAME, LOCAL_USER_NAME, LOCAL_TABLE_NAME FROM SYSTEM_.SYS_REPL_ITEMS_";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String rep = nullToEmpty(rs.getString(1)).trim();
                String user = nullToEmpty(rs.getString(2)).trim();
                String table = nullToEmpty(rs.getString(3)).trim();
                ctx.addGauge("replication_item", Labels.of("replication", rep, "local_user", user, "local_table", table), 1);
            }
        }
    }

    @ScrapeMetric(value = {"user_password_life_time", "user_password_lock_time", "user_failed_login_attempts"}, catchSchemaError = true)
    private void scrapeUserPasswordPolicy(ScrapeContext ctx) throws SQLException {
        String sql = "SELECT USER_NAME, PASSWORD_LIFE_TIME, PASSWORD_LOCK_TIME, FAILED_LOGIN_ATTEMPTS FROM SYSTEM_.SYS_USERS_";
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String user = nullToEmpty(rs.getString(1)).trim();
                if (user.isEmpty()) continue;
                Labels labels = Labels.of("user_name", user);
                ctx.addGauge("user_password_life_time", labels, rs.getLong(2));
                ctx.addGauge("user_password_lock_time", labels, rs.getLong(3));
                ctx.addGauge("user_failed_login_attempts", labels, rs.getLong(4));
            }
        }
    }

    @ScrapeMetric("service_thread_count")
    private void scrapeServiceThread(ScrapeContext ctx) throws SQLException {
        String sql = """
            SELECT TYPE AS NAME, COUNT(*) AS CNT FROM V$SERVICE_THREAD GROUP BY TYPE \
            UNION ALL SELECT STATE AS NAME, COUNT(*) AS CNT FROM V$SERVICE_THREAD GROUP BY STATE \
            UNION ALL SELECT RUN_MODE AS NAME, COUNT(*) AS CNT FROM V$SERVICE_THREAD GROUP BY RUN_MODE
            """;
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                long cnt = rs.getLong(2);
                if (name != null) ctx.addGauge("service_thread_count", Labels.of("kind", "thread", "value", name), cnt);
            }
        }
    }

    @ScrapeMetric("sysstat")
    private void scrapeSysstat(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT NAME, VALUE FROM V$SYSSTAT WHERE SEQNUM < 88")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long val = rs.getLong(2);
                if (name != null) ctx.addGauge("sysstat", Labels.of("name", name), val);
            }
        }
    }

    @ScrapeMetric(value = "property", catchSchemaError = true)
    private void scrapeProperty(ScrapeContext ctx) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery("SELECT NAME, VALUE1 FROM V$PROPERTY")) {
            while (rs.next()) {
                String name = nullToEmpty(rs.getString(1)).trim();
                if (name.isEmpty()) continue;
                String valueStr = nullToEmpty(rs.getString(2)).trim();
                ctx.addGauge("property", Labels.of("name", name, "value", valueStr), 1);
            }
        }
    }

    private long queryLong(ScrapeContext ctx, String sql) throws SQLException {
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Sequence metrics: existence (all sequences) and current value (replicated sequences). Uses SYS_TABLES_ and SYS_REPL_ITEMS_. */
    @ScrapeMetric(value = {"sequence_exists", "sequence_current_value"}, catchSchemaError = true)
    private void scrapeSequenceUsage(ScrapeContext ctx) throws SQLException {
        // Enumerate every sequence (TABLE_TYPE='S'); one series each (value 1).
        try (ResultSet rs = ctx.statement().executeQuery(
                "SELECT U.USER_NAME, T.TABLE_NAME FROM SYSTEM_.SYS_TABLES_ T, SYSTEM_.SYS_USERS_ U "
                + "WHERE T.USER_ID = U.USER_ID AND T.TABLE_TYPE = 'S' AND U.USER_NAME <> 'SYSTEM_'")) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1)).trim();
                String seq = nullToEmpty(rs.getString(2)).trim();
                if (schema.isEmpty() || seq.isEmpty()) continue;
                ctx.addGauge("sequence_exists", Labels.of("schema", schema, "sequence", seq), 1);
            }
        } catch (SQLException e) {
            log.debug("Sequence enumeration (SYS_TABLES_ TABLE_TYPE=S) failed: {}", e.getMessage());
        }

        // Current value for replicated sequences: sync tables are named SEQUENCE_NAME$SEQ (SYS_REPL_ITEMS_.LOCAL_TABLE_NAME).
        // Sequence attributes (min/max/cycle/cache) have no read-only source in supported versions, so they are not collected.
        // Read all sync-table rows first, then query each: a Statement allows only one open
        // ResultSet, so running the inner LAST_SYNC_SEQ query while the outer cursor is open
        // would close it and cut the loop short after a single row.
        // The same sync table can appear under multiple replications in SYS_REPL_ITEMS_; dedupe by
        // (schema, table) so we emit one series per sequence (and avoid duplicate-label errors).
        String sql = "SELECT LOCAL_USER_NAME, LOCAL_TABLE_NAME FROM SYSTEM_.SYS_REPL_ITEMS_ WHERE UPPER(LOCAL_TABLE_NAME) LIKE '%$SEQ%'";
        List<String[]> syncTables = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ResultSet rs = ctx.statement().executeQuery(sql)) {
            while (rs.next()) {
                String schema = nullToEmpty(rs.getString(1)).trim();
                String tableName = nullToEmpty(rs.getString(2)).trim();
                if (schema.isEmpty() || tableName.isEmpty()) continue;
                if (seen.add(schema + " " + tableName)) syncTables.add(new String[]{schema, tableName});
            }
        }
        int count = 0;
        final int maxSequences = 100;
        for (String[] st : syncTables) {
            if (count >= maxSequences) break;
            String schema = st[0];
            String tableName = st[1];
            long current;
            try {
                String q = "SELECT LAST_SYNC_SEQ FROM \"" + schema.replace("\"", "\"\"") + "\".\"" + tableName.replace("\"", "\"\"") + "\"";
                try (ResultSet inner = ctx.statement().executeQuery(q)) {
                    current = inner.next() ? inner.getLong(1) : 0;
                }
            } catch (SQLException e) {
                log.trace("Sequence {}.\"{}\": {}", schema, tableName, e.getMessage());
                continue;
            }
            count++;
            String baseName = tableName.replaceAll("\\$[sS][eE][qQ]$", "");
            ctx.addGauge("sequence_current_value", Labels.of("schema", schema, "sequence", baseName), current);
        }
    }
}
