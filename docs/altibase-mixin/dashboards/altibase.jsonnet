// Altibase Grafana dashboard. Config: dashboardTitle, dashboardUid, dashboardTags, dashboardRefresh.
local config = import '../config.libsonnet';
local panels = import '../lib/panels.libsonnet';

local inst = 'instance=~"$instance"';

// ---- Stat panels (gauges) at top: y=0,4,8,12 ----
local statRows = [
  [
    panels.statPanel(1, 'Up', 'altibase_exporter_last_scrape_success{' + inst + '}', { h: 4, w: 4, x: 0, y: 0 }, 'short', { fieldConfig: { thresholds: { mode: 'absolute', steps: [{ color: 'red', value: null }, { color: 'green', value: 1 }] } } }),
    panels.statPanel(2, 'Scrape duration', 'altibase_scrape_duration_seconds{' + inst + '}', { h: 4, w: 4, x: 4, y: 0 }, 's'),
    panels.statPanel(7, 'Buffer pool hit ratio', 'altibase_buffer_pool_hit_ratio{' + inst + '}', { h: 4, w: 4, x: 8, y: 0 }, 'percentunit'),
    panels.statPanel(8, 'Archive mode', 'altibase_archive_mode{' + inst + '}', { h: 4, w: 4, x: 12, y: 0 }),
    panels.statPanel(26, 'Instance working time', 'altibase_instance_working_time_seconds{' + inst + '}', { h: 4, w: 4, x: 16, y: 0 }, 's'),
    panels.statPanel(27, 'Altibase version', 'altibase_version_info{' + inst + '}', { h: 4, w: 4, x: 20, y: 0 }, 'short', { options: { textMode: 'name' } }),
  ],
  [
    panels.statPanel(48, 'Exporter version', 'altibase_exporter_build_info{' + inst + '}', { h: 4, w: 4, x: 0, y: 4 }, 'short', { options: { textMode: 'name' } }),
    panels.statPanel(28, 'Buffer pool victim fails', 'altibase_buffer_pool_victim_fails{' + inst + '}', { h: 4, w: 4, x: 4, y: 4 }),
    panels.statPanel(29, 'LF prepare wait count', 'altibase_lf_prepare_wait_count{' + inst + '}', { h: 4, w: 4, x: 8, y: 4 }),
    panels.statPanel(9, 'Lock hold count', 'altibase_lock_hold_count{' + inst + '}', { h: 4, w: 4, x: 12, y: 4 }),
    panels.statPanel(10, 'Lock wait count', 'altibase_lock_wait_count{' + inst + '}', { h: 4, w: 4, x: 16, y: 4 }),
    panels.statPanel(11, 'Long-run query count', 'altibase_long_run_query_count{' + inst + '}', { h: 4, w: 4, x: 20, y: 4 }),
  ],
  [
    panels.statPanel(12, 'UTRANS query count', 'altibase_utrans_query_count{' + inst + '}', { h: 4, w: 4, x: 0, y: 8 }),
    panels.statPanel(13, 'Fullscan query count', 'altibase_fullscan_query_count{' + inst + '}', { h: 4, w: 4, x: 4, y: 8 }),
    panels.statPanel(42, 'Lock hold (top 1)', 'altibase_lock_hold_detail{' + inst + '}', { h: 4, w: 4, x: 8, y: 8 }, 'short', { fieldConfig: { thresholds: { mode: 'absolute', steps: [{ color: 'green', value: null }, { color: 'yellow', value: 1 }] } } }),
    panels.statPanel(43, 'Lock wait (top 1)', 'altibase_lock_wait_detail{' + inst + '}', { h: 4, w: 4, x: 12, y: 8 }, 'short', { fieldConfig: { thresholds: { mode: 'absolute', steps: [{ color: 'green', value: null }, { color: 'red', value: 1 }] } } }),
    panels.statPanel(44, 'Tx memory view SCN (top 1)', 'altibase_tx_of_memory_view_scn{' + inst + '}', { h: 4, w: 4, x: 16, y: 8 }),
    panels.statPanel(45, 'Long-run query (top 1)', 'altibase_long_run_query_detail{' + inst + '}', { h: 4, w: 4, x: 20, y: 8 }),
  ],
  [
    panels.statPanel(46, 'UTRANS query (top 1)', 'altibase_utrans_query_detail{' + inst + '}', { h: 4, w: 4, x: 0, y: 12 }),
    panels.statPanel(47, 'Fullscan query (top 1)', 'altibase_fullscan_query_detail{' + inst + '}', { h: 4, w: 4, x: 4, y: 12 }),
    panels.statPanel(67, 'TxMgr total', 'altibase_transaction_manager_count{status="total", ' + inst + '}', { h: 4, w: 4, x: 8, y: 12 }),
    panels.statPanel(68, 'TxMgr active', 'altibase_transaction_manager_count{status="active", ' + inst + '}', { h: 4, w: 4, x: 12, y: 12 }),
    panels.statPanel(69, 'Locked tables', 'sum(altibase_lock_table{' + inst + '}) or vector(0)', { h: 4, w: 4, x: 16, y: 12 }),
    panels.statPanel(70, 'Trigger count', 'altibase_trigger_count{schema="total", ' + inst + '} or vector(0)', { h: 4, w: 4, x: 20, y: 12 }, 'short'),
  ],
];

local ts(expr, legend) = { expr: expr, legendFormat: legend };
local tsRow(y, left, right) = [
  panels.timeSeriesPanel(left.id, left.title, left.targets, { h: 6, w: 12, x: 0, y: y }, left.unit, std.get(left, 'extra', {})),
  panels.timeSeriesPanel(right.id, right.title, right.targets, { h: 6, w: 12, x: 12, y: y }, right.unit, std.get(right, 'extra', {})),
];
local tsFull(id, title, targets, y, h, unit) = panels.timeSeriesPanel(id, title, targets, { h: h, w: 24, x: 0, y: y }, unit);

local timeSeriesRows = [
  tsRow(16, { id: 3, title: 'Sessions', targets: [ts('altibase_sessions{status="total", ' + inst + '}', 'total - {{instance}}'), ts('altibase_sessions{status="active", ' + inst + '}', 'active - {{instance}}')], unit: 'short' }, { id: 4, title: 'Statements', targets: [ts('altibase_statements{status="total", ' + inst + '}', 'total - {{instance}}'), ts('altibase_statements{status="active", ' + inst + '}', 'active - {{instance}}')], unit: 'short' }),
  tsRow(22, { id: 14, title: 'Memory (memstat)', targets: [ts('altibase_memstat_max_total_bytes{' + inst + '}', 'memstat max total - {{instance}}'), ts('altibase_memstat_alloc_bytes{' + inst + '}', 'memstat alloc - {{instance}}')], unit: 'bytes' }, { id: 15, title: 'Table usage (memory / disk)', targets: [ts('altibase_memory_table_usage_bytes{' + inst + '}', 'memory table - {{instance}}'), ts('altibase_disk_table_usage_bytes{' + inst + '}', 'disk table - {{instance}}')], unit: 'bytes' }),
  tsRow(28, { id: 16, title: 'Buffer pool hit ratio', targets: [ts('altibase_buffer_pool_hit_ratio{' + inst + '}', '')], unit: 'percentunit' }, { id: 17, title: 'Locks (hold / wait)', targets: [ts('altibase_lock_hold_count{' + inst + '}', 'hold - {{instance}}'), ts('altibase_lock_wait_count{' + inst + '}', 'wait - {{instance}}')], unit: 'short' }),
  tsRow(34, { id: 18, title: 'Logfile (oldest / current / gap)', targets: [ts('altibase_logfile_oldest{' + inst + '}', 'oldest - {{instance}}'), ts('altibase_logfile_current{' + inst + '}', 'current - {{instance}}'), ts('altibase_logfile_gap{' + inst + '}', 'gap - {{instance}}')], unit: 'short' }, { id: 19, title: 'Replication (senders / receivers / gap)', targets: [ts('altibase_replication_sender_count{' + inst + '}', 'senders - {{instance}}'), ts('altibase_replication_receiver_count{' + inst + '}', 'receivers - {{instance}}'), ts('sum(altibase_replication_gap{' + inst + '})', 'replication gap (sum) - {{instance}}')], unit: 'short' }),
  tsRow(40, { id: 20, title: 'Tablespace usage ratio (by name)', targets: [ts('altibase_tablespace_usage_ratio{' + inst + '}', '{{tbs_name}} - {{instance}}')], unit: 'percentunit' }, { id: 21, title: 'Replication gap (by name)', targets: [ts('altibase_replication_gap{' + inst + '}', '{{replication}} - {{instance}}')], unit: 'short' }),
  tsRow(46, { id: 22, title: 'Query counts (long-run / UTRANS / fullscan)', targets: [ts('altibase_long_run_query_count{' + inst + '}', 'long run - {{instance}}'), ts('altibase_utrans_query_count{' + inst + '}', 'utrans - {{instance}}'), ts('altibase_fullscan_query_count{' + inst + '}', 'fullscan - {{instance}}')], unit: 'short' }, { id: 23, title: 'GC gap (by name)', targets: [ts('altibase_gc_gap{' + inst + '}', '{{gc_name}} - {{instance}}')], unit: 'short' }),
  tsRow(52, { id: 24, title: 'File I/O reads (by file)', targets: [ts('altibase_file_io_reads{' + inst + '}', '{{file_name}} - {{instance}}')], unit: 'short' }, { id: 25, title: 'File I/O writes (by file)', targets: [ts('altibase_file_io_writes{' + inst + '}', '{{file_name}} - {{instance}}')], unit: 'short' }),
  tsRow(58, { id: 30, title: 'Memstat usage ratio (by name)', targets: [ts('altibase_memstat_usage_ratio{' + inst + '}', '{{name}} - {{instance}}')], unit: 'percentunit' }, { id: 31, title: 'Memstat bytes (by name)', targets: [ts('altibase_memstat_bytes{' + inst + '}', '{{name}} ({{type}}) - {{instance}}')], unit: 'bytes' }),
  tsRow(64, { id: 32, title: 'Tablespace total bytes (by name)', targets: [ts('altibase_tablespace_total_bytes{' + inst + '}', '{{tbs_name}} - {{instance}}')], unit: 'bytes' }, { id: 33, title: 'Tablespace state (by name)', targets: [ts('altibase_tablespace_state{' + inst + '}', '{{tbs_name}} - {{instance}}')], unit: 'short', extra: { fieldConfig: { mappings: [{ options: { '0': { color: 'red', index: 0, text: 'OFFLINE' } }, type: 'value' }, { options: { '1': { color: 'green', index: 0, text: 'ONLINE' } }, type: 'value' }] } } }),
  tsRow(70, { id: 34, title: 'File I/O wait (avg single-block read, s)', targets: [ts('altibase_file_io_wait_seconds{' + inst + '}', '{{file_name}} - {{instance}}')], unit: 's' }, { id: 35, title: 'Event time waited (system / session)', targets: [ts('altibase_system_event_time_waited_seconds{' + inst + '}', 'sys {{event}} - {{instance}}'), ts('altibase_session_event_time_waited_seconds{' + inst + '}', 'sess {{event}} - {{instance}}')], unit: 's' }),
  tsRow(76, { id: 36, title: 'Memory table usage (per table)', targets: [ts('altibase_memory_table_usage_bytes_per_table{' + inst + '}', '{{table_name}} - {{instance}}')], unit: 'bytes' }, { id: 37, title: 'Disk table usage (per table)', targets: [ts('altibase_disk_table_usage_bytes_per_table{' + inst + '}', '{{table_name}} - {{instance}}')], unit: 'bytes' }),
  tsRow(82, { id: 38, title: 'Queue usage (by table)', targets: [ts('altibase_queue_usage_bytes{' + inst + '}', '{{table_name}} - {{instance}}')], unit: 'bytes' }, { id: 39, title: 'Segment usage (by tablespace)', targets: [ts('altibase_segment_usage_bytes{' + inst + '}', '{{name}} - {{instance}}')], unit: 'bytes' }),
  [tsFull(40, 'Service thread count (by kind / value)', [ts('altibase_service_thread_count{' + inst + '}', '{{kind}} {{value}} - {{instance}}')], 88, 6, 'short')],
  [tsFull(41, 'Sysstat (V$SYSSTAT by name)', [ts('altibase_sysstat{' + inst + '}', '{{name}} - {{instance}}')], 94, 8, 'short')],
  tsRow(104, { id: 50, title: 'Sessions by user', targets: [ts('altibase_sessions_by_user{' + inst + '}', '{{user_name}} ({{status}}) - {{instance}}')], unit: 'short' }, { id: 51, title: 'Statements by user', targets: [ts('altibase_statements_by_user{' + inst + '}', '{{user_name}} ({{status}}) - {{instance}}')], unit: 'short' }),
  tsRow(110, { id: 52, title: 'Replication sender XSN / Commit XSN', targets: [ts('altibase_replication_sender_xsn{' + inst + '}', 'XSN {{replication}} - {{instance}}'), ts('altibase_replication_sender_commit_xsn{' + inst + '}', 'COMMIT_XSN {{replication}} - {{instance}}')], unit: 'short' }, { id: 53, title: 'Replication sender net error flag', targets: [ts('altibase_replication_sender_net_error_flag{' + inst + '}', '{{replication}} - {{instance}}')], unit: 'short', extra: { fieldConfig: { mappings: [{ options: { '0': { color: 'green', index: 0, text: 'OK' } }, type: 'value' }, { options: { '1': { color: 'red', index: 0, text: 'Network error' } }, type: 'value' }] } } }),
  tsRow(116, { id: 54, title: 'Replication gap size (bytes)', targets: [ts('altibase_replication_gap_size_bytes{' + inst + '}', '{{replication}} - {{instance}}')], unit: 'bytes' }, { id: 55, title: 'Replication receiver apply XSN', targets: [ts('altibase_replication_receiver_apply_xsn{' + inst + '}', '{{replication}} - {{instance}}')], unit: 'short' }),
  tsRow(122, { id: 56, title: 'Job state / exec count', targets: [ts('altibase_job_state{' + inst + '}', '{{job_name}} - {{instance}}'), ts('altibase_job_exec_count{' + inst + '}', 'exec {{job_name}} - {{instance}}')], unit: 'short' }, { id: 57, title: 'Job error code / interval', targets: [ts('altibase_job_error_code{' + inst + '}', '{{job_name}} - {{instance}}'), ts('altibase_job_interval{' + inst + '}', 'interval {{job_name}} - {{instance}}')], unit: 'short' }),
  tsRow(128, { id: 58, title: 'Disk tablespace curr / max bytes', targets: [ts('altibase_tablespace_disk_curr_bytes{' + inst + '}', 'curr {{tbs_name}} - {{instance}}'), ts('altibase_tablespace_disk_max_bytes{' + inst + '}', 'max {{tbs_name}} - {{instance}}')], unit: 'bytes' }, { id: 59, title: 'Disk tablespace usage ratio', targets: [ts('altibase_tablespace_disk_usage_ratio{' + inst + '}', '{{tbs_name}} - {{instance}}')], unit: 'percentunit' }),
  tsRow(134, { id: 60, title: 'Sequence count by schema', targets: [ts('count by (schema) (altibase_sequence_exists{' + inst + '})', '{{schema}} - {{instance}}')], unit: 'short' }, { id: 61, title: 'Sequence current value', targets: [ts('altibase_sequence_current_value{' + inst + '}', '{{schema}}.{{sequence}} - {{instance}}')], unit: 'short' }),
  tsRow(140, { id: 62, title: 'User password life / lock time', targets: [ts('altibase_user_password_life_time{' + inst + '}', 'life {{user_name}} - {{instance}}'), ts('altibase_user_password_lock_time{' + inst + '}', 'lock {{user_name}} - {{instance}}')], unit: 'short' }, { id: 63, title: 'User failed login attempts', targets: [ts('altibase_user_failed_login_attempts{' + inst + '}', '{{user_name}} - {{instance}}')], unit: 'short' }),
  tsRow(146, { id: 64, title: 'Table size (bytes) by table', targets: [ts('altibase_table_size_bytes{' + inst + '}', '{{schema}}.{{table_name}} ({{type}}) - {{instance}}')], unit: 'bytes' }, { id: 65, title: 'Replication items (replicated tables)', targets: [ts('altibase_replication_item{' + inst + '}', '{{replication}} {{local_user}}.{{local_table}} - {{instance}}')], unit: 'short' }),
  [tsFull(66, 'Property (V$PROPERTY)', [ts('altibase_property{' + inst + '}', '{{name}} = {{value}} - {{instance}}')], 152, 8, 'short')],
  tsRow(160, { id: 71, title: 'Transaction manager (total / active)', targets: [ts('altibase_transaction_manager_count{status="total", ' + inst + '}', 'total - {{instance}}'), ts('altibase_transaction_manager_count{status="active", ' + inst + '}', 'active - {{instance}}')], unit: 'short' }, { id: 72, title: 'Trigger count (by schema)', targets: [ts('altibase_trigger_count{schema!="total", ' + inst + '}', '{{schema}} - {{instance}}')], unit: 'short' }),
  tsRow(166, { id: 73, title: 'Locked tables (count)', targets: [ts('sum(altibase_lock_table{' + inst + '}) or vector(0)', 'locked tables - {{instance}}')], unit: 'short' }, { id: 74, title: 'Index alloc size (bytes by index)', targets: [ts('altibase_index_alloc_size_bytes{' + inst + '}', '{{schema}}.{{table_name}} / {{index_name}} - {{instance}}')], unit: 'bytes' }),
];

local flattenRows(rows) = std.foldr(function(row, acc) row + acc, rows, []);
local allStatPanels = flattenRows(statRows);
local allTimeSeriesPanels = flattenRows(timeSeriesRows);
local panelsList = allStatPanels + allTimeSeriesPanels;

// __inputs and __requires are required by Grafana.com upload ("Export for sharing externally" format).
// Without these, Grafana.com shows "Old dashboard JSON format".
{
  '__inputs': [
    {
      name: 'DS_PROMETHEUS',
      label: 'Prometheus',
      description: '',
      type: 'datasource',
      pluginId: 'prometheus',
      pluginName: 'Prometheus',
    },
  ],
  '__requires': [
    { type: 'grafana', id: 'grafana', name: 'Grafana', version: '9.0.0' },
    { type: 'datasource', id: 'prometheus', name: 'Prometheus', version: '1.0.0' },
    { type: 'panel', id: 'stat', name: 'Stat', version: '' },
    { type: 'panel', id: 'timeseries', name: 'Time series', version: '' },
  ],
  annotations: { list: [] },
  editable: true,
  fiscalYearStartMonth: 0,
  graphTooltip: 1,
  id: null,
  links: [],
  liveNow: false,
  panels: panelsList,
  refresh: config.dashboardRefresh,
  schemaVersion: 38,
  style: 'dark',
  tags: config.dashboardTags,
  templating: {
    list: [
      {
        current: {},
        hide: 0,
        includeAll: false,
        label: 'Prometheus',
        multi: false,
        name: 'DS_PROMETHEUS',
        options: [],
        query: 'prometheus',
        refresh: 1,
        regex: '',
        skipUrlSync: false,
        type: 'datasource',
      },
      {
        allValue: '.*',
        current: { selected: true, text: 'All', value: '$__all' },
        datasource: { type: 'prometheus', uid: '${DS_PROMETHEUS}' },
        definition: 'label_values(altibase_exporter_last_scrape_success, instance)',
        hide: 0,
        includeAll: true,
        label: 'Instance',
        multi: false,
        name: 'instance',
        options: [],
        query: 'label_values(altibase_exporter_last_scrape_success, instance)',
        refresh: 1,
        regex: '',
        skipUrlSync: false,
        sort: 1,
        type: 'query',
      },
    ],
  },
  time: { from: 'now-1h', to: 'now' },
  timepicker: {},
  timezone: 'browser',
  title: config.dashboardTitle,
  uid: config.dashboardUid,
  version: 1,
}
