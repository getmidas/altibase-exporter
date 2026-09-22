# Altibase Prometheus Exporter

A Prometheus exporter for [Altibase](https://www.altibase.com/).

For local build, run, and releases, see [DEVELOPMENT.md](DEVELOPMENT.md).

---

## Where to run

The exporter needs only JDBC access to Altibase (no ODBC or Altibase install on the host).

| Where | When to use | Example |
|-------|-------------|--------|
| **Host (VM / bare metal)** | Same machine as Altibase, or any server that can reach Altibase. Java 25 required. | Download JAR from [Releases](https://github.com/f9n/altibase-exporter/releases), set `ALTIBASE_*` env, run `java -jar altibase-exporter.jar`. Metrics at `http://<host>:9399/metrics`. |
| **Container (Docker)** | Isolated run without installing Java; same host or another host. | Pull image from GHCR (`ghcr.io/f9n/altibase-exporter`), then `docker run` with env vars. Details below. |
| **Kubernetes** | Exporter in a cluster (e.g. separate from Altibase); ConfigMap + Secret for config. | Edit [examples/k8s/configmap.yaml](examples/k8s/configmap.yaml), create secret, `kubectl apply -f examples/k8s/`. Details below. |

---

## Running

**Environment variables:**

| Variable | Description | Default |
|----------|-------------|---------|
| `ALTIBASE_SERVER` | Altibase server host | 127.0.0.1 |
| `ALTIBASE_PORT` | Altibase port | 20300 |
| `ALTIBASE_USER` | Database user | sys |
| `ALTIBASE_PASSWORD` | Password | manager |
| `ALTIBASE_DATABASE` | Database name | mydb |
| `WEB_LISTEN_PORT` | Exporter HTTP port | 9399 |
| `ALTIBASE_QUERIES_FILE` | Path to custom queries YAML (optional) | — |
| `ALTIBASE_DISABLED_METRICS` | Comma-separated list of built-in metric keys to disable (e.g. `sysstat`, `replication_gap`, `property`). Use `*` to disable **all** built-in metrics (e.g. to run only custom queries). | — |
| `LOG_LEVEL` | Log level: `DEBUG`, `INFO`, `WARN`, `ERROR`. Logs are JSON (structured) to stdout. | INFO |

Run: set env or use command-line flags (see [DEVELOPMENT.md](DEVELOPMENT.md)). Metrics at `http://localhost:9399/metrics`.

The JDBC connection is set to **read-only** (`Connection.setReadOnly(true)`), so the exporter cannot modify data; the database will reject any write.

---

## Metrics

The exporter collects **built-in** metrics from Altibase V$ system views and **JVM/process** metrics (`jvm_*`, `process_*`) from the Prometheus Java client (memory, threads, GC, CPU). All built-in metrics are on by default.

To disable specific metrics:

- Set `ALTIBASE_DISABLED_METRICS` to a comma-separated list of **metric keys** (metric name without the `altibase_` prefix), e.g. `ALTIBASE_DISABLED_METRICS=sysstat,replication_gap`.
- Use `ALTIBASE_DISABLED_METRICS=*` to disable **all** built-in metrics — useful to run the exporter with only custom queries (skips the built-in V$ scrape entirely).
- Identity/health metrics (`altibase_exporter_build_info`, `altibase_exporter_last_scrape_success`, `altibase_scrape_duration_seconds`, `altibase_version_info`) cannot be disabled.


| Metric | Labels | Description |
|--------|--------|-------------|
| `altibase_exporter_build_info` | — | Exporter build identity (Info). |
| `altibase_exporter_last_scrape_success` | — | 1 if last scrape succeeded, 0 otherwise. |
| `altibase_scrape_duration_seconds` | — | Duration of the last scrape in seconds. |
| `altibase_instance_working_time_seconds` | — | Instance working time. |
| `altibase_version_info` | — | Altibase server version (Info). |
| `altibase_archive_mode` | — | Archive mode 0/1. |
| `altibase_sessions` | status | Total or active session count. |
| `altibase_statements` | status | Total or active statement count. |
| `altibase_sessions_by_user` | user_name, status | Session count per user. |
| `altibase_statements_by_user` | user_name, status | Statement count per user. |
| `altibase_memstat_max_total_bytes` | — | Sum of memstat max total size. |
| `altibase_memstat_alloc_bytes` | — | Sum of memstat allocated size. |
| `altibase_memstat_usage_ratio` | name | Per-name usage ratio (top 10). |
| `altibase_memstat_bytes` | name, type | Per-name max/alloc size. |
| `altibase_buffer_pool_hit_ratio` | — | Buffer pool hit ratio. |
| `altibase_buffer_pool_victim_fails` | — | Buffer pool victim failures. |
| `altibase_logfile_oldest` | — | Oldest active logfile number. |
| `altibase_logfile_current` | — | Current logfile number. |
| `altibase_logfile_gap` | — | Logfile gap (current − oldest). |
| `altibase_lf_prepare_wait_count` | — | Logfile prepare wait count. |
| `altibase_lock_hold_count` | — | Number of lock holds. |
| `altibase_lock_wait_count` | — | Number of lock waits. |
| `altibase_lock_table` | schema, table_name, trans_id, lock_desc | Locked tables (one series per lock; value 1). |
| `altibase_long_run_query_count` | — | Long-running queries (&gt; 1s). |
| `altibase_utrans_query_count` | — | Uncommitted transaction queries. |
| `altibase_fullscan_query_count` | — | Full-scan queries (excl. exporter). |
| `altibase_transaction_manager_count` | status | V$TRANSACTION_MGR total/active count. |
| `altibase_trigger_count` | schema | Trigger count from SYSTEM_.SYS_TRIGGERS_: total (schema="total") and per schema (schema=user name). |
| `altibase_replication_sender_count` | — | Replication sender count. |
| `altibase_replication_receiver_count` | — | Replication receiver count. |
| `altibase_replication_peer` | replication, role, instance_role, status, mode, peer | Replication peer (who, status, mode). |
| `altibase_replication_sender_xsn` | replication | XSN last transmitted by sender. |
| `altibase_replication_sender_commit_xsn` | replication | Local commit XSN. |
| `altibase_replication_sender_net_error_flag` | replication | Sender network error 0=OK, 1=error. |
| `altibase_replication_gap` | replication | Replication gap by name. |
| `altibase_replication_gap_size_bytes` | replication | Replication gap size in bytes. |
| `altibase_replication_gap_rep_last_sn` | replication | Last log SN on source. |
| `altibase_replication_gap_rep_sn` | replication | Replication position SN. |
| `altibase_replication_receiver_apply_xsn` | replication | Applied XLog position. |
| `altibase_replication_item` | replication, local_user, local_table | Replicated tables (1 per table). |
| `altibase_sequence_exists` | schema, sequence | One series per sequence (value 1); from SYS_TABLES_. Use `count()` for totals. |
| `altibase_sequence_current_value` | schema, sequence | Current value (replicated sequences). |
| `altibase_job_state` | job_name | 0=idle, 1=executing. |
| `altibase_job_exec_count` | job_name | Execution count. |
| `altibase_job_error_code` | job_name | Last error code. |
| `altibase_job_interval` | job_name | Job interval. |
| `altibase_tablespace_disk_curr_bytes` | tbs_name | Disk tablespace current size. |
| `altibase_tablespace_disk_max_bytes` | tbs_name | Disk tablespace max size. |
| `altibase_tablespace_disk_usage_ratio` | tbs_name | Disk tablespace usage 0–1. |
| `altibase_tablespace_total_bytes` | tbs_name | Memory tablespace total size. |
| `altibase_tablespace_usage_ratio` | tbs_name | Memory tablespace usage 0–1. |
| `altibase_tablespace_state` | tbs_name, state | ONLINE/OFFLINE. |
| `altibase_user_password_life_time` | user_name | Password life time. |
| `altibase_user_password_lock_time` | user_name | Password lock time. |
| `altibase_user_failed_login_attempts` | user_name | Failed login attempts. |
| `altibase_memory_table_usage_bytes` | — | Total memory table usage. |
| `altibase_disk_table_usage_bytes` | — | Total disk table usage. |
| `altibase_memory_table_usage_bytes_per_table` | schema, table_name | Per table, top 5. |
| `altibase_disk_table_usage_bytes_per_table` | schema, table_name | Per table, top 5. |
| `altibase_table_size_bytes` | schema, table_name, tablespace, type | Size per user table (memory/disk). |
| `altibase_service_thread_count` | kind, value | Count by type/state/run_mode. |
| `altibase_sysstat` | name | System statistic values. |
| `altibase_property` | name, value | Server configuration (pg_settings–style). |
| `altibase_gc_gap` | gc_name | GC gap by name. |
| `altibase_file_io_reads` | name | Cumulative physical reads per file. |
| `altibase_file_io_writes` | name | Cumulative physical writes per file. |
| `altibase_file_io_wait_seconds` | name | Avg single-block read wait per file (s). |
| `altibase_system_event_time_waited_seconds` | name | System event time waited (non-Idle). |
| `altibase_session_event_time_waited_seconds` | name | Session event time waited (non-Idle). |
| `altibase_queue_usage_bytes` | schema, table_name | Queue table usage. |
| `altibase_segment_usage_bytes` | name | Segment usage by tablespace. |
| `altibase_index_alloc_size_bytes` | schema, table_name, tablespace, index_name, index_type | Index allocation size in bytes per index. |
| `altibase_index_metadata` | schema, table_name, index_name, index_id, tablespace, is_unique, column_cnt | Index metadata (value 1 per index). |
| `altibase_index_information_mem` | schema, object_type, object_name, tablespace, index_name, index_type | Index info for memory table and queue (value 1 per index). |
| `altibase_lock_hold_detail` | (multiple) | Top 1 lock hold. |
| `altibase_lock_wait_detail` | (multiple) | Top 1 lock wait. |
| `altibase_tx_of_memory_view_scn` | (multiple) | Top 1 tx memory view SCN. |
| `altibase_long_run_query_detail` | (multiple) | Top 1 long-running query. |
| `altibase_utrans_query_detail` | (multiple) | Top 1 uncommitted transaction query. |
| `altibase_fullscan_query_detail` | (multiple) | Top 1 full-scan query. |

Replication metrics align with the [Altibase Replication Manual](https://docs.altibase.com/). See `/metrics` for exact label names.

---

## Custom queries (SQL exporter style)

Use a YAML **queries file** to run your own SQL and expose results as gauges. Queries are grouped into **jobs** (sql_exporter style) — see [examples/queries.yaml](examples/queries.yaml).

```yaml
jobs:
  - name: heavy
    interval: 5m          # run these queries every 5m in the background; /metrics serves the cache
    queries:
      - name: database_pages
        help: "Memory page counts"
        labels: [db_name]
        values: [mem_alloc, mem_free]
        query: "SELECT DB_NAME AS db_name, MEM_ALLOC_PAGE_COUNT AS mem_alloc, MEM_FREE_PAGE_COUNT AS mem_free FROM V$DATABASE"
  - name: cheap
    queries:              # no interval → run at scrape time
      - { name: ping, help: "Ping", query: "SELECT 1 AS value" }
```

**Setup**
- Set path with `ALTIBASE_QUERIES_FILE` or `-altibase.queries-file` (e.g. `ALTIBASE_QUERIES_FILE=examples/queries.yaml`). If the file is missing or empty, only built-in metrics are collected.
- A top-level `queries:` (without `jobs:`) is **not** supported — wrap queries under a job.

**Query fields** — **name**, **help**, **query** (alias **sql**), optional **labels** (alias **label_columns**) and **values**:
- **Single value:** the numeric column is named `value` (or the first non-label column) → one metric `altibase_custom_<name>`.
- **Multiple values:** list value columns under **values** → one metric per value column, named `altibase_custom_<name>_<value_column>`. Any column not in `values` (and not declared in `labels`) becomes a label. Lets you reuse `sql_exporter`-style query bodies (e.g. `pg_stat_user_tables`) almost verbatim.
- Every custom metric carries the **`altibase_custom_`** prefix, so names cannot clash with built-in `altibase_*` metrics.

**Jobs and intervals**
- **`interval`** (e.g. `30s`, `1m`, `5m`, `1h30m`): the job's queries run on a background scheduler at that cadence; `/metrics` returns the **last cached** result instantly. Decouples DB load from Prometheus' `scrape_interval` (useful for slow/heavy queries). Interval jobs use a **dedicated read-only connection** so background collection never contends with scrape-time collectors.
- **No `interval`**: queries run synchronously at scrape time (effective frequency = Prometheus `scrape_interval`).
- **`connections:`** under a job is **ignored** — the exporter always uses the single connection from `ALTIBASE_*`. Run one exporter per target.

**Notes**
- **Tables/views must exist** in the DB the exporter connects to. If you see `Custom query failed: ... Table or view was not found`, use qualified names in SQL (e.g. `SCHEMA_NAME.TABLE_NAME`) or point the exporter at the correct database.

---

## Prometheus configuration

[examples/prometheus/prometheus.yml](examples/prometheus/prometheus.yml) contains `rule_files` (alert rules) and a scrape config. Use it as-is from the repo root or merge into your `prometheus.yml`. Replace `<exporter-host>` with the exporter host or IP (e.g. `localhost` or Kubernetes service name).

---

## Docker

**1. Pull the image** from [GitHub Container Registry](https://github.com/f9n/altibase-exporter/pkgs/container/altibase-exporter):

```bash
docker pull ghcr.io/f9n/altibase-exporter:latest
```

Or use a specific release tag (e.g. `v1.0.0`): `docker pull ghcr.io/f9n/altibase-exporter:v1.0.0`.

**2. Run the container** (set your Altibase connection with env vars):

```bash
docker run -d --name altibase-exporter -p 9399:9399 \
  -e ALTIBASE_SERVER=<host> \
  -e ALTIBASE_PORT=<port> \
  -e ALTIBASE_USER=<user> \
  -e ALTIBASE_PASSWORD=<password> \
  ghcr.io/f9n/altibase-exporter:latest
```

**3. Check metrics:**

```bash
curl http://localhost:9399/metrics
```

Or open `http://localhost:9399` / `http://localhost:9399/metrics` in a browser.

**4. Stop and remove:**

```bash
docker stop altibase-exporter
docker rm altibase-exporter
```

**Optional — build the image locally** (from project root): `docker build -t altibase-exporter .` then use `altibase-exporter` as the image name in `docker run`.

---

## Kubernetes

Example manifests are in the [examples/k8s/](examples/k8s/) directory. The exporter runs as a Deployment; connection settings come from a ConfigMap and a Secret. The deployment uses `ghcr.io/f9n/altibase-exporter:latest` by default (or use a release tag, e.g. `:v1.0.0`).

**1. Create the Secret** (use real credentials; do not commit secrets):

```bash
kubectl create secret generic altibase-exporter-secret \
  --from-literal=ALTIBASE_USER=<user> \
  --from-literal=ALTIBASE_PASSWORD=<password>
```

**2. Edit and apply manifests:**

- [examples/k8s/configmap.yaml](examples/k8s/configmap.yaml) — set `ALTIBASE_SERVER`, `ALTIBASE_PORT`, `ALTIBASE_DATABASE` (and optionally `WEB_LISTEN_PORT`).
- [examples/k8s/deployment.yaml](examples/k8s/deployment.yaml) — uses `ghcr.io/f9n/altibase-exporter:latest` by default; change the image/tag if needed.

```bash
kubectl apply -f examples/k8s/configmap.yaml
kubectl apply -f examples/k8s/secret.yaml    # or use the create secret command above
kubectl apply -f examples/k8s/deployment.yaml
kubectl apply -f examples/k8s/service.yaml
```

**3. Prometheus scraping** — the Service has annotations (`prometheus.io/scrape`, `prometheus.io/port`, `prometheus.io/path`) so Prometheus can discover and scrape it when using Kubernetes endpoints discovery with relabel configs that honour these annotations (e.g. many Helm-based Prometheus setups).

Pods must be able to reach the Altibase server (e.g. same VPC, or exposed host/port).

**4. Custom queries (optional)** — [examples/k8s/configmap.yaml](examples/k8s/configmap.yaml) includes an optional `queries.yaml` key. To use it, deploy with [examples/k8s/deployment-with-queries.yaml](examples/k8s/deployment-with-queries.yaml) instead of [examples/k8s/deployment.yaml](examples/k8s/deployment.yaml) (same ConfigMap; that deployment mounts the key as a file and sets `ALTIBASE_QUERIES_FILE`).

---

## Grafana

Import the dashboard from [Grafana.com (ID: 24792)](https://grafana.com/grafana/dashboards/24792) or use the JSON file at [docs/altibase-mixin/generated/altibase.json](docs/altibase-mixin/generated/altibase.json). See [docs/altibase-mixin/README.md](docs/altibase-mixin/README.md) for source and build.

