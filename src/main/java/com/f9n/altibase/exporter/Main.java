package com.f9n.altibase.exporter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Altibase Prometheus exporter (JDBC). Env: ALTIBASE_*, WEB_LISTEN_PORT, ALTIBASE_QUERIES_FILE, ALTIBASE_DISABLED_METRICS. */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String METRICS_PATH = "/metrics";
    private static final int VALIDITY_CHECK_TIMEOUT_SEC = 2;
    private static final long CONNECTION_CLOSE_TIMEOUT_MS = 3000L;

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int envInt(String key, int def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Connection connectWithTimeout(String jdbcUrl, Properties props, int timeoutSeconds,
                                                  String server, int port, String database) throws SQLException {
        final SQLException[] holder = new SQLException[1];
        final Connection[] result = new Connection[1];
        Thread connectThread = new Thread(() -> {
            try {
                result[0] = DriverManager.getConnection(jdbcUrl, props);
            } catch (SQLException e) {
                holder[0] = e;
            }
        }, "altibase-connect");
        connectThread.setDaemon(true);
        connectThread.start();
        try {
            connectThread.join(timeoutSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Connection interrupted: jdbc={}:{} database={}", server, port, database);
            throw new SQLException("Connection interrupted", e);
        }
        if (holder[0] != null) {
            log.error("Connection failed: jdbc={}:{} database={} error={}", server, port, database, holder[0].getMessage(), holder[0]);
            throw holder[0];
        }
        if (result[0] != null) {
            return result[0];
        }
        log.error("Connection timeout: jdbc={}:{} database={} timeout_seconds={}", server, port, database, timeoutSeconds);
        throw new SQLException("Connection timeout after " + timeoutSeconds + " seconds: jdbc=" + server + ":" + port + "/" + database);
    }

    static Set<String> parseDisabledMetrics(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ExporterConfig buildConfig(String[] args) {
        String server = env("ALTIBASE_SERVER", "127.0.0.1");
        int port = envInt("ALTIBASE_PORT", 20300);
        String user = env("ALTIBASE_USER", "sys");
        String password = env("ALTIBASE_PASSWORD", "manager");
        String database = env("ALTIBASE_DATABASE", "mydb");
        int listenPort = envInt("WEB_LISTEN_PORT", 9399);
        String queriesFile = env("ALTIBASE_QUERIES_FILE", "");
        int connectTimeoutSeconds = envInt("ALTIBASE_CONNECT_TIMEOUT", 10);

        for (String arg : args) {
            if (arg.startsWith("-altibase.server=")) server = arg.substring(17).trim();
            else if (arg.startsWith("-altibase.port=")) port = Integer.parseInt(arg.substring(15).trim());
            else if (arg.startsWith("-altibase.user=")) user = arg.substring(15).trim();
            else if (arg.startsWith("-altibase.password=")) password = arg.substring(19).trim();
            else if (arg.startsWith("-altibase.database=")) database = arg.substring(19).trim();
            else if (arg.startsWith("-altibase.queries-file=")) queriesFile = arg.substring(23).trim();
            else if (arg.startsWith("-altibase.connect-timeout=")) connectTimeoutSeconds = Integer.parseInt(arg.substring(27).trim());
            else if (arg.startsWith("-web.listen-address=:")) listenPort = Integer.parseInt(arg.substring(19).trim());
        }

        String exporterVersion = Main.class.getPackage().getImplementationVersion();
        if (exporterVersion == null) exporterVersion = "0.0.0";
        Set<String> disabledMetrics = parseDisabledMetrics(env("ALTIBASE_DISABLED_METRICS", ""));

        return new ExporterConfig(server, port, user, password, database, listenPort, queriesFile, connectTimeoutSeconds, disabledMetrics, exporterVersion);
    }

    public static void main(String[] args) throws InterruptedException {
        if (System.getProperty("log.level") == null) {
            String level = System.getenv("LOG_LEVEL");
            System.setProperty("log.level", (level == null || level.isBlank()) ? "INFO" : level.trim());
        }

        ExporterConfig config = buildConfig(args);

        Properties props = new Properties();
        props.setProperty("user", config.user());
        props.setProperty("password", config.password());

        log.info("Connecting to Altibase: jdbc={}:{} database={} timeout_seconds={}", config.server(), config.port(), config.database(), config.connectTimeoutSeconds());
        Connection conn = null;
        try {
            conn = connectWithTimeout(config.jdbcUrl(), props, config.connectTimeoutSeconds(), config.server(), config.port(), config.database());
            if (!conn.isValid(VALIDITY_CHECK_TIMEOUT_SEC)) {
                SQLException e = new SQLException("Database connection validation failed: isValid(" + VALIDITY_CHECK_TIMEOUT_SEC + ") returned false");
                log.error("Connection validation failed: jdbc={}:{} database={} error={}", config.server(), config.port(), config.database(), e.getMessage(), e);
                System.exit(-1);
            }
            conn.setReadOnly(true);
        } catch (SQLException e) {
            log.error("Connection failed: jdbc={}:{} database={} error={}", config.server(), config.port(), config.database(), e.getMessage(), e);
            System.exit(-1);
        }
        final Connection connFinal = conn;
        log.info("Database connection established (read-only)");
        try (var stmt = conn.createStatement()) {
            stmt.execute("exec set_client_info('altibase-exporter')");
        } catch (SQLException ignored) {}

        JvmMetrics.builder().register();
        log.info("JVM metrics registered");

        PrometheusRegistry.defaultRegistry.register(new AltibaseCollector(conn, config.disabledMetrics(), config.exporterVersion()));
        log.info("Altibase metrics registered (custom collector, on-the-fly): disabled={}", config.disabledMetrics().isEmpty() ? "none" : config.disabledMetrics());

        CustomQueryCollector customCollector = null;
        Connection bgConn = null;
        if (config.queriesFile() != null && !config.queriesFile().isBlank()) {
            try {
                List<CustomQueryCollector.JobDef> jobs = QueriesLoader.load(Path.of(config.queriesFile()));
                int queryCount = jobs.stream().mapToInt(j -> j.queries().size()).sum();
                if (queryCount > 0) {
                    boolean hasBackground = jobs.stream().anyMatch(j -> j.interval() != null);
                    if (hasBackground) {
                        // Interval jobs run on a dedicated read-only connection so the background scheduler
                        // never shares a (non-thread-safe) JDBC connection with the scrape-time collectors.
                        bgConn = connectWithTimeout(config.jdbcUrl(), props, config.connectTimeoutSeconds(), config.server(), config.port(), config.database());
                        bgConn.setReadOnly(true);
                        try (var stmt = bgConn.createStatement()) {
                            stmt.execute("exec set_client_info('altibase-exporter-custom')");
                        } catch (SQLException ignored) {}
                        log.info("Custom queries background connection established (read-only)");
                    }
                    customCollector = new CustomQueryCollector(conn, bgConn, jobs);
                    PrometheusRegistry.defaultRegistry.register(customCollector);
                    log.info("Custom queries loaded: file={} jobs={} queries={} background={}", config.queriesFile(), jobs.size(), queryCount, hasBackground);
                }
            } catch (Exception e) {
                log.warn("Custom queries file load failed: file={} error={}", config.queriesFile(), e.getMessage());
            }
        }
        final CustomQueryCollector customCollectorFinal = customCollector;
        final Connection bgConnFinal = bgConn;

        HttpHandler rootHandler = (HttpExchange exchange) -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || path.isEmpty()) {
                String html = "<html><head><title>Altibase Exporter</title></head><body>\n"
                        + "<h1>Altibase Exporter</h1>\n<p><a href=\"" + METRICS_PATH + "\">Metrics</a></p>\n</body></html>";
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else if ("/-/healthy".equals(path)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        };

        HTTPServer httpServer = null;
        try {
            httpServer = HTTPServer.builder()
                    .port(config.listenPort())
                    .defaultHandler(rootHandler)
                    .buildAndStart();
        } catch (IOException e) {
            log.error("HTTP server failed to start: port={} error={}", config.listenPort(), e.getMessage(), e);
            System.exit(-1);
        }
        if (httpServer == null) System.exit(-1);
        final HTTPServer httpServerFinal = httpServer;
        log.info("HTTP server started: port={} path={}", config.listenPort(), METRICS_PATH);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            try {
                httpServerFinal.close();
            } catch (Exception e) {
                log.error("HTTP server close failed: {}", e.getMessage());
            }
            if (customCollectorFinal != null) {
                try {
                    customCollectorFinal.close();
                } catch (Exception e) {
                    log.error("Custom query collector close failed: {}", e.getMessage());
                }
            }
            Thread closeThread = new Thread(() -> {
                try {
                    connFinal.close();
                } catch (SQLException e) {
                    log.error("Connection close failed: {}", e.getMessage());
                }
                if (bgConnFinal != null) {
                    try {
                        bgConnFinal.close();
                    } catch (SQLException e) {
                        log.error("Background connection close failed: {}", e.getMessage());
                    }
                }
            });
            closeThread.setDaemon(true);
            closeThread.start();
            try {
                closeThread.join(CONNECTION_CLOSE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (closeThread.isAlive()) {
                log.warn("Connection close did not complete in {}ms, exiting", CONNECTION_CLOSE_TIMEOUT_MS);
            }
            log.info("Exiting");
        }));

        log.info("Altibase exporter ready: port={} jdbc={}:{} metrics=http://localhost:{}{}", httpServerFinal.getPort(), config.server(), config.port(), httpServerFinal.getPort(), METRICS_PATH);
        Thread.currentThread().join();
    }
}
