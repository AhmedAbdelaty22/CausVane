package io.causvane.explain;

import io.causvane.analysis.TechDetector;
import io.causvane.model.ErrorGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Advice for the technologies the application turned out to use, added to the general "What to do"
 * steps: the setting to check, the command to run or the place to look in that particular framework,
 * database or platform. Only technologies the log itself mentioned are used.
 */
public final class TechAdvice {

    /** A piece of advice: applies when the technology was found and the problem is of one of the kinds. */
    private record Tip(String tech, List<String> categories, String hint, String step) {}

    private static final List<Tip> TIPS = List.of(
            // connection pools and databases
            new Tip("HikariCP", List.of("Database", "Network", "Performance", "Capacity"), "hikari|connection is not available|pool",
                    "HikariCP: compare `maximumPoolSize` with how many requests run at once, and set `leakDetectionThreshold` "
                            + "(for example 20000 ms) to log the code that keeps a connection without returning it."),
            new Tip("PostgreSQL", List.of("Database"), null,
                    "PostgreSQL: look at `pg_stat_activity` for sessions that are idle in transaction or waiting on a lock, "
                            + "and compare the connections in use with `max_connections`."),
            new Tip("PostgreSQL", List.of("Network"), "postgres|psql|5432",
                    "PostgreSQL: check that the server accepts connections from this machine (`listen_addresses` and "
                            + "`pg_hba.conf`) and that its port, usually 5432, can be reached."),
            new Tip("MySQL", List.of("Database"), null,
                    "MySQL: `SHOW PROCESSLIST` shows what each connection is doing, and `SHOW ENGINE INNODB STATUS` "
                            + "describes the latest deadlock; compare the connections in use with `max_connections`."),
            new Tip("SQL Server", List.of("Database"), null,
                    "SQL Server: `sys.dm_exec_requests` shows blocked sessions; the system_health session keeps the latest deadlock graphs."),
            new Tip("Oracle Database", List.of("Database"), null,
                    "Oracle: look up the `ORA-` code in the message; `V$SESSION` shows blocked sessions and what they wait for."),
            new Tip("MongoDB", List.of("Database", "Network"), "mongo",
                    "MongoDB: check the connection string's `maxPoolSize` and timeouts, and `db.currentOp()` for slow operations."),
            new Tip("Hibernate", List.of("Database", "Data"), "sql|constraint|hibernate|jdbc|transaction",
                    "Hibernate: turn on `org.hibernate.SQL` logging briefly to see the exact statement that failed."),
            new Tip("Entity Framework", List.of("Database", "Data"), "sql|constraint|entityframework|dbupdate|transaction",
                    "Entity Framework: enable `EnableSensitiveDataLogging` in a test environment to see the statement and values that failed."),
            new Tip("SQLAlchemy", List.of("Database"), null,
                    "SQLAlchemy: check `pool_size`, `max_overflow` and `pool_pre_ping=True`, which replaces connections the database closed."),
            // messaging and caches
            new Tip("Kafka", List.of("Messaging", "Network"), "kafka|broker|topic|partition|consumer|producer",
                    "Kafka: check that `bootstrap.servers` reaches the brokers, and look at consumer lag "
                            + "(`kafka-consumer-groups --describe`) to see whether messages are piling up."),
            new Tip("RabbitMQ", List.of("Messaging", "Network"), "rabbit|amqp|queue|channel",
                    "RabbitMQ: the management page shows each queue's backlog and its consumers; check the connection's heartbeat setting."),
            new Tip("Redis", List.of("Network", "Capacity", "Memory"), "redis|jedis|lettuce",
                    "Redis: `INFO clients` and `INFO memory` show connected clients and memory against `maxmemory`; check the client's timeout."),
            // memory and platform
            new Tip("Kubernetes", List.of("Memory", "Crash", "Resources"), "memory|oom|killed|exit code 137|sigkill",
                    "Kubernetes: `kubectl describe pod` shows OOMKilled and restart counts; compare the container's memory limit "
                            + "with what the application is allowed to use."),
            new Tip("Docker", List.of("Memory", "Crash", "Resources"), "memory|oom|killed|exit code 137|sigkill",
                    "Docker: `docker inspect` shows whether the container was OOM-killed; `docker stats` shows memory use against its limit."),
            // frameworks
            new Tip("Spring Boot", List.of("Configuration", "Lifecycle", "Deployment"), null,
                    "Spring Boot: the last `Caused by` of a start-up failure names the bean or property that failed; starting with `--debug` "
                            + "prints the condition report."),
            new Tip("Spring Boot", List.of("Network", "Performance"), "timeout|timed out",
                    "Spring Boot: timeouts of outgoing calls are set on the client (RestTemplate, WebClient or Feign), not by the server."),
            new Tip("Django", List.of("Database"), null,
                    "Django: `CONN_MAX_AGE` controls how long connections are kept; `python manage.py showmigrations` shows migrations not yet applied."),
            new Tip("Ruby on Rails", List.of("Database"), null,
                    "Rails: the `pool` value in `config/database.yml` must be at least the number of threads (Puma `max_threads`)."),
            new Tip("ASP.NET Core", List.of("Configuration", "Lifecycle"), null,
                    "ASP.NET Core: run once with `ASPNETCORE_ENVIRONMENT=Development` to get the detailed start-up error."),
            new Tip("Node.js", List.of("Memory"), null,
                    "Node.js: the heap limit is set with `--max-old-space-size`; a heap snapshot from `--inspect` shows what fills it."),
            new Tip("Java", List.of("Memory"), null,
                    "Java: start with `-XX:+HeapDumpOnOutOfMemoryError` to capture a heap dump at the moment it happens, and compare `-Xmx` "
                            + "with the memory the machine or container has."),
            new Tip("Nginx", List.of("Network"), "502|503|504|bad gateway|upstream",
                    "Nginx: its error log names the upstream that failed; `proxy_read_timeout` controls how long it waits."),
            new Tip("AWS", List.of("Security"), "access|denied|credential|forbidden|unauthori",
                    "AWS: check the IAM role or keys the application runs with; CloudTrail shows which call was denied and why."));

    private TechAdvice() {}

    /** Extra steps for this problem in this application, most specific first; empty when none apply. */
    public static List<String> steps(ErrorGroup group, Explanation explanation, TechDetector.Profile profile) {
        List<String> out = new ArrayList<>();
        if (profile == null) {
            return out;
        }
        StringBuilder all = new StringBuilder().append(group.errorType()).append(' ').append(group.message()).append(' ')
                .append(group.rootCauseType()).append(' ').append(group.rootCauseMessage());
        if (!group.samples().isEmpty()) {
            group.samples().get(0).lines().stream().limit(40).forEach(line -> all.append('\n').append(line));
        }
        String text = all.toString().toLowerCase(Locale.ROOT);
        for (Tip tip : TIPS) {
            if (!present(tip.tech(), profile) || !tip.categories().contains(explanation.category())) {
                continue;
            }
            if (tip.hint() != null && !java.util.regex.Pattern.compile(tip.hint()).matcher(text).find()) {
                continue;
            }
            if (out.size() < 3) {
                out.add(tip.step());
            }
        }
        return out;
    }

    private static boolean present(String tech, TechDetector.Profile profile) {
        return switch (tech) {
            case "Java" -> profile.language() == io.causvane.model.Language.JAVA;
            case "Node.js" -> profile.language() == io.causvane.model.Language.NODE;
            default -> profile.has(tech);
        };
    }
}
