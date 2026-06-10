package com.f9n.altibase.exporter;

import java.util.List;
import java.util.Map;

/** Metric name and help text; key = name without "altibase_" prefix. */
public final class AltibaseMetricDefs {

    private static final String NS = "altibase";

    public static String name(String key) {
        return NS + "_" + key;
    }

    public static List<String> allKeys() {
        return HELP.keySet().stream().sorted().toList();
    }

    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry("exporter_build_info", "Exporter build identity (Info)."),
            Map.entry("version", "Altibase server version (Info)."),
            Map.entry("exporter_last_scrape_success", "1 if last scrape succeeded, 0 otherwise."),
            Map.entry("scrape_duration_seconds", "Duration of the last scrape in seconds."),
            Map.entry("instance_working_time_seconds", "Instance working time (V$INSTANCE)."),
            Map.entry("archive_mode", "Archive mode 0/1 (V$ARCHIVE)."),
            Map.entry("sessions", "Session count; label status: total, active."),
            Map.entry("statements", "Statement count; label status: total, active."),
            Map.entry("sessions_by_user", "Session count per database user (V$SESSION); labels user_name, status (total, active)."),
            Map.entry("statements_by_user", "Statement count per database user (V$STATEMENT, V$SESSION); labels user_name, status (total, active)."),
            Map.entry("memstat_max_total_bytes", "Sum of MAX_TOTAL_SIZE from V$MEMSTAT."),
            Map.entry("memstat_alloc_bytes", "Sum of ALLOC_SIZE from V$MEMSTAT."),
            Map.entry("buffer_pool_hit_ratio", "Buffer pool hit ratio (V$BUFFPOOL_STAT)."),
            Map.entry("buffer_pool_victim_fails", "Buffer pool victim failures (V$BUFFPOOL_STAT)."),
            Map.entry("logfile_oldest", "Oldest active logfile number (V$ARCHIVE)."),
            Map.entry("logfile_current", "Current logfile number (V$ARCHIVE)."),
            Map.entry("logfile_gap", "Logfile gap: current oldest (V$ARCHIVE)."),
            Map.entry("lf_prepare_wait_count", "Logfile prepare wait count (V$LFG)."),
            Map.entry("lock_hold_count", "Number of lock holds (V$LOCK_STATEMENT STATE=0)."),
            Map.entry("lock_wait_count", "Number of lock waits (V$LOCK_STATEMENT STATE=1)."),
            Map.entry("long_run_query_count", "Long-running queries (execute time > 1s)."),
            Map.entry("utrans_query_count", "Uncommitted transaction queries (UTRANS)."),
            Map.entry("fullscan_query_count", "Full-scan queries (excluding exporter sessions)."),
            Map.entry("replication_sender_count", "Replication senders (V$REPSENDER)."),
            Map.entry("replication_receiver_count", "Replication receivers (V$REPRECEIVER)."),
            Map.entry("replication_peer", "Replication peers: who this instance replicates with (V$REPSENDER, V$REPRECEIVER); labels replication, role (sender/receiver), instance_role (master/slave), status (active/stopped/retry), mode (lazy/eager/unknown), peer (host:port)."),
            Map.entry("replication_sender_xsn", "V$REPSENDER XSN (XLog sequence number last transmitted by sender)."),
            Map.entry("replication_sender_commit_xsn", "V$REPSENDER COMMIT_XSN (Local Commit XSN; committed log most recently read by sender)."),
            Map.entry("replication_sender_net_error_flag", "V$REPSENDER NET_ERROR_FLAG (0=OK, 1=network error)."),
            Map.entry("memory_table_usage_bytes", "Total memory table usage (V$MEMTBL_INFO)."),
            Map.entry("disk_table_usage_bytes", "Total disk table usage (V$DISKTBL_INFO)."),
            Map.entry("memstat_usage_ratio", "Per-name memstat usage ratio, top 10."),
            Map.entry("memstat_bytes", "Per-name memstat max_total_size and alloc_size."),
            Map.entry("gc_gap", "GC gap by GC name (V$MEMGC)."),
            Map.entry("tablespace_total_bytes", "Tablespace total size (memory)."),
            Map.entry("tablespace_state", "Tablespace state 1=ONLINE, 0=OFFLINE."),
            Map.entry("tablespace_usage_ratio", "Tablespace usage ratio (memory)."),
            Map.entry("file_io_reads", "Cumulative physical reads per file (V$FILESTAT)."),
            Map.entry("file_io_writes", "Cumulative physical writes per file (V$FILESTAT)."),
            Map.entry("file_io_wait_seconds", "Avg single-block read wait per file (seconds)."),
            Map.entry("system_event_time_waited_seconds", "System event time waited, non-Idle."),
            Map.entry("session_event_time_waited_seconds", "Session event time waited, non-Idle."),
            Map.entry("memory_table_usage_bytes_per_table", "Memory table usage per table, top 5."),
            Map.entry("disk_table_usage_bytes_per_table", "Disk table usage per table, top 5."),
            Map.entry("table_size_bytes", "Table name and size in bytes for all user tables (memory and disk); labels schema, table_name, tablespace, type (memory|disk)."),
            Map.entry("queue_usage_bytes", "Queue table usage."),
            Map.entry("segment_usage_bytes", "Segment usage by tablespace."),
            Map.entry("service_thread_count", "Service thread count by type/state/run_mode (V$SERVICE_THREAD)."),
            Map.entry("sysstat", "V$SYSSTAT values."),
            Map.entry("replication_gap", "Replication gap by name (V$REPGAP REP_GAP)."),
            Map.entry("replication_gap_size_bytes", "Replication gap size in bytes by name (V$REPGAP REP_GAP_SIZE)."),
            Map.entry("replication_gap_rep_last_sn", "V$REPGAP REP_LAST_SN (last log SN on source)."),
            Map.entry("replication_gap_rep_sn", "V$REPGAP REP_SN (replication position)."),
            Map.entry("replication_receiver_apply_xsn", "V$REPRECEIVER APPLY_XSN (applied XLog position) per replication."),
            Map.entry("job_state", "Job state 0=idle 1=executing (SYSTEM_.SYS_JOBS_)."),
            Map.entry("job_exec_count", "Job execution count (SYSTEM_.SYS_JOBS_)."),
            Map.entry("job_error_code", "Job last error code (SYSTEM_.SYS_JOBS_)."),
            Map.entry("job_interval", "Job interval (SYSTEM_.SYS_JOBS_)."),
            Map.entry("tablespace_disk_curr_bytes", "Disk tablespace current size (V$DATAFILES)."),
            Map.entry("tablespace_disk_max_bytes", "Disk tablespace max size (V$DATAFILES)."),
            Map.entry("tablespace_disk_usage_ratio", "Disk tablespace usage ratio (V$DATAFILES)."),
            Map.entry("replication_item", "Replicated tables: one series per table (SYSTEM_.SYS_REPL_ITEMS_; labels replication, local_user, local_table; value 1)."),
            Map.entry("sequence_exists", "Sequence existence: one series per sequence (SYS_TABLES_ TABLE_TYPE='S'); labels schema, sequence; value 1. Use count() for totals."),
            Map.entry("sequence_current_value", "Sequence current value (LAST_SYNC_SEQ from sync table; replicated sequences from SYS_REPL_ITEMS_)."),
            Map.entry("user_password_life_time", "User password life time (SYSTEM_.SYS_USERS_)."),
            Map.entry("user_password_lock_time", "User password lock time (SYSTEM_.SYS_USERS_)."),
            Map.entry("user_failed_login_attempts", "User failed login attempts (SYSTEM_.SYS_USERS_)."),
            Map.entry("lock_hold_detail", "Top 1 lock hold (detail labels)."),
            Map.entry("lock_wait_detail", "Top 1 lock wait (detail labels)."),
            Map.entry("tx_of_memory_view_scn", "Top 1 tx with memory view SCN (detail labels)."),
            Map.entry("long_run_query_detail", "Top 1 long-running query (detail labels)."),
            Map.entry("utrans_query_detail", "Top 1 uncommitted transaction query (detail labels)."),
            Map.entry("fullscan_query_detail", "Top 1 full-scan query (detail labels)."),
            Map.entry("index_alloc_size_bytes", "Index allocation size in bytes per index (V$SEGMENT, V$INDEX); labels schema, table_name, tablespace, index_name, index_type."),
            Map.entry("index_metadata", "Index metadata (SYSTEM_.SYS_INDICES_); labels schema, table_name, index_name, index_id, tablespace, is_unique, column_cnt; value 1."),
            Map.entry("index_information_mem", "Index info for memory table and queue (V$INDEX, V$MEM_TABLESPACES); labels schema, object_type, object_name, tablespace, index_name, index_type; value 1."),
            Map.entry("lock_table", "Locked tables (V$LOCK + SYS_TABLES_); one series per lock; labels table_name, trans_id, lock_desc; value 1."),
            Map.entry("property", "Server configuration from V$PROPERTY (like pg_settings); labels name, value."),
            Map.entry("transaction_manager_count", "V$TRANSACTION_MGR total and active count; label status: total, active."),
            Map.entry("trigger_count", "Trigger count from SYSTEM_.SYS_TRIGGERS_: total (schema=total) and per schema (schema=user name).")
    );

    public static String help(String key) {
        return HELP.getOrDefault(key, "");
    }

    private AltibaseMetricDefs() {}
}
