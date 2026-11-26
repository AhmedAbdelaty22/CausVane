package io.causvane.analysis;

import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.Language;
import io.causvane.model.LogEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Recognises what kind of application wrote a log: its language, framework, database, message broker and
 * platform. It looks only at the log itself (package and library names in stack traces and logger names,
 * typical start-up lines and error messages), entirely offline, and is used to say "Looks like: a Java
 * Spring Boot service using PostgreSQL and Kafka" and to tailor the advice to those technologies.
 */
public final class TechDetector implements Consumer<LogEvent> {

    /** What part a technology plays; decides where it goes in the description. */
    public enum Kind { FRAMEWORK, LIBRARY, SERVER, DATABASE, POOL, MESSAGING, CACHE, SEARCH, CLOUD, PLATFORM }

    /**
     * A technology and the lower-case text that gives it away.
     *
     * @param language the language it implies, or null
     * @param service  true for frameworks that serve requests, so the application is called a service
     */
    public record Tech(String name, Kind kind, Language language, boolean service, List<String> signals) {}

    /** Every technology recognised, most specific first within each kind. */
    public static final List<Tech> CATALOGUE = List.of(
            // frameworks
            tech("Spring Boot", Kind.FRAMEWORK, Language.JAVA, true, "org.springframework.boot", " o.s.b.", "springapplication", ":: spring boot ::"),
            tech("Spring", Kind.FRAMEWORK, Language.JAVA, true, "org.springframework.", " o.s.web.", " o.s.w.", " o.s.c."),
            tech("Quarkus", Kind.FRAMEWORK, Language.JAVA, true, "io.quarkus"),
            tech("Micronaut", Kind.FRAMEWORK, Language.JAVA, true, "io.micronaut"),
            tech("Jakarta EE", Kind.FRAMEWORK, Language.JAVA, true, "jakarta.ejb", "javax.ejb", "org.jboss.as", "wildfly"),
            tech("ASP.NET Core", Kind.FRAMEWORK, Language.DOTNET, true, "microsoft.aspnetcore"),
            tech("Django", Kind.FRAMEWORK, Language.PYTHON, true, "django.", "/django/"),
            tech("FastAPI", Kind.FRAMEWORK, Language.PYTHON, true, "fastapi", "starlette"),
            tech("Flask", Kind.FRAMEWORK, Language.PYTHON, true, "flask", "werkzeug"),
            tech("Celery", Kind.FRAMEWORK, Language.PYTHON, false, "celery"),
            tech("NestJS", Kind.FRAMEWORK, Language.NODE, true, "@nestjs", "[nest]"),
            tech("Next.js", Kind.FRAMEWORK, Language.NODE, true, "next/dist", "node_modules/next/"),
            tech("Express", Kind.FRAMEWORK, Language.NODE, true, "node_modules/express", "express:router", "express/lib"),
            tech("Ruby on Rails", Kind.FRAMEWORK, Language.RUBY, true, "actionpack", "activerecord", "railties", "actioncontroller"),
            tech("Laravel", Kind.FRAMEWORK, Language.PHP, true, "illuminate\\", "laravel"),
            tech("Symfony", Kind.FRAMEWORK, Language.PHP, true, "symfony\\", "symfony/"),
            tech("Gin", Kind.FRAMEWORK, Language.GO, true, "gin-gonic", "[gin]", "[gin-debug]"),
            // libraries worth naming
            tech("Hibernate", Kind.LIBRARY, Language.JAVA, false, "org.hibernate", " o.h.", "hhh0"),
            tech("Entity Framework", Kind.LIBRARY, Language.DOTNET, false, "microsoft.entityframeworkcore", "system.data.entity"),
            tech("SQLAlchemy", Kind.LIBRARY, Language.PYTHON, false, "sqlalchemy"),
            tech("gRPC", Kind.LIBRARY, null, false, "io.grpc", "grpc"),
            // servers
            tech("Tomcat", Kind.SERVER, Language.JAVA, false, "org.apache.catalina", " o.a.c.c.", "tomcatwebserver", "tomcat started"),
            tech("Jetty", Kind.SERVER, Language.JAVA, false, "org.eclipse.jetty"),
            tech("Netty", Kind.SERVER, Language.JAVA, false, "io.netty"),
            tech("Gunicorn", Kind.SERVER, Language.PYTHON, false, "gunicorn"),
            tech("Uvicorn", Kind.SERVER, Language.PYTHON, false, "uvicorn"),
            tech("Nginx", Kind.SERVER, null, false, "nginx"),
            tech("IIS", Kind.SERVER, Language.DOTNET, false, "microsoft-iis", "w3wp"),
            // databases
            tech("PostgreSQL", Kind.DATABASE, null, false, "org.postgresql", "psqlexception", "psycopg", "postgres", "npgsql", "pq: "),
            tech("MySQL", Kind.DATABASE, null, false, "com.mysql", "mysql", "mariadb"),
            tech("Oracle Database", Kind.DATABASE, null, false, "oracle.jdbc", "ora-0", "ora-1", "ora-2"),
            tech("SQL Server", Kind.DATABASE, null, false, "sqlserverexception", "microsoft.data.sqlclient", "system.data.sqlclient", "mssql"),
            tech("MongoDB", Kind.DATABASE, null, false, "com.mongodb", "mongoerror", "mongoservererror", "pymongo", "mongodb://", "mongoose"),
            tech("Cassandra", Kind.DATABASE, null, false, "com.datastax", "cassandra"),
            tech("SQLite", Kind.DATABASE, null, false, "sqlite"),
            tech("HikariCP", Kind.POOL, Language.JAVA, false, "hikaripool", "com.zaxxer.hikari"),
            // messaging, caches, search
            tech("Kafka", Kind.MESSAGING, null, false, "org.apache.kafka", "kafka"),
            tech("RabbitMQ", Kind.MESSAGING, null, false, "rabbitmq", "amqpexception", "amqp://", "org.springframework.amqp"),
            tech("ActiveMQ", Kind.MESSAGING, null, false, "activemq"),
            tech("Amazon SQS", Kind.MESSAGING, null, false, "sqsexception", "amazonsqs", "sqs.amazonaws"),
            tech("Redis", Kind.CACHE, null, false, "redis:", "redis.", "redis ", "redis://", "jedis", "io.lettuce"),
            tech("Memcached", Kind.CACHE, null, false, "memcache"),
            tech("Elasticsearch", Kind.SEARCH, null, false, "elasticsearch", "org.opensearch"),
            // cloud and platform
            tech("AWS", Kind.CLOUD, null, false, "amazonaws", "software.amazon.awssdk", "aws-sdk", "botocore", "boto3"),
            tech("Azure", Kind.CLOUD, null, false, "com.azure.", "azure.core", "microsoft.azure"),
            tech("Google Cloud", Kind.CLOUD, null, false, "com.google.cloud", "googleapis.com"),
            tech("Kubernetes", Kind.PLATFORM, null, false, "kubernetes", "k8s", "kubelet", "oomkilled"),
            tech("Docker", Kind.PLATFORM, null, false, "docker"));

    /** Characters of an entry's first lines that are looked at. */
    private static final int LINE_CHARS = 400;
    private static final int LINES_PER_EVENT = 60;
    /** After this many characters only problems are still looked at, so huge logs stay fast. */
    private static final long NORMAL_BUDGET = 40_000_000L;
    private static final long TOTAL_BUDGET = 120_000_000L;

    private final long[] hits = new long[CATALOGUE.size()];
    private long scanned;

    private static Tech tech(String name, Kind kind, Language language, boolean service, String... signals) {
        return new Tech(name, kind, language, service, List.of(signals));
    }

    @Override
    public void accept(LogEvent event) {
        if (scanned >= TOTAL_BUDGET || (scanned >= NORMAL_BUDGET && !event.isProblem())) {
            return;
        }
        boolean[] seen = new boolean[CATALOGUE.size()];
        List<String> lines = event.lines();
        int count = Math.min(lines.size(), LINES_PER_EVENT);
        for (int i = 0; i <= count; i++) {
            String line = i < count ? lines.get(i) : event.message();
            if (line == null || line.isEmpty()) {
                continue;
            }
            String text = (line.length() > LINE_CHARS ? line.substring(0, LINE_CHARS) : line).toLowerCase(Locale.ROOT);
            scanned += text.length();
            for (int t = 0; t < seen.length; t++) {
                if (!seen[t]) {
                    for (String signal : CATALOGUE.get(t).signals()) {
                        if (text.contains(signal)) {
                            seen[t] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (int t = 0; t < seen.length; t++) {
            if (seen[t]) {
                hits[t]++;
            }
        }
    }

    /** Technologies mentioned, with how many entries mentioned each, most mentioned first. */
    public Map<String, Long> hits() {
        List<Integer> order = new ArrayList<>();
        for (int t = 0; t < hits.length; t++) {
            if (hits[t] > 0) {
                order.add(t);
            }
        }
        order.sort((a, b) -> Long.compare(hits[b], hits[a]));
        Map<String, Long> out = new LinkedHashMap<>();
        order.forEach(t -> out.put(CATALOGUE.get(t).name(), hits[t]));
        return out;
    }

    // ------------------------------------------------------------------ the description

    /**
     * What the application looks like.
     *
     * @param sentence     "a Java Spring Boot service using PostgreSQL, HikariCP and Kafka", or null when nothing was recognised
     * @param technologies every technology recognised, for tailoring advice
     */
    public record Profile(String sentence, Language language, Set<String> technologies) {
        public boolean has(String name) {
            return technologies.contains(name);
        }

        public static Profile none() {
            return new Profile(null, Language.UNKNOWN, Set.of());
        }
    }

    public static Profile profile(AnalysisResult result) {
        Map<String, Long> found = result.contents().technologies();
        Language language = language(result, found);
        List<Tech> techs = new ArrayList<>();
        for (Tech t : CATALOGUE) {
            if (found.containsKey(t.name())) {
                techs.add(t);
            }
        }
        // a more specific name makes the general one redundant
        if (has(techs, "Spring Boot")) {
            techs.removeIf(t -> t.name().equals("Spring"));
        }
        Set<String> names = new LinkedHashSet<>();
        techs.forEach(t -> names.add(t.name()));
        if (techs.isEmpty() && language == Language.UNKNOWN) {
            return new Profile(null, language, names);
        }

        Tech framework = techs.stream().filter(t -> t.kind() == Kind.FRAMEWORK)
                .max((a, b) -> Long.compare(found.get(a.name()), found.get(b.name()))).orElse(null);
        boolean service = techs.stream().anyMatch(t -> t.service() || t.kind() == Kind.SERVER);
        StringBuilder sb = new StringBuilder();
        String languageName = languageName(language);
        String first = languageName != null ? languageName : framework != null ? framework.name() : null;
        sb.append(first == null ? "an" : article(first)).append(' ');
        if (languageName != null) {
            sb.append(languageName).append(' ');
        }
        if (framework != null) {
            sb.append(framework.name()).append(' ');
        }
        boolean worker = framework != null && framework.name().equals("Celery");
        sb.append(worker ? "worker" : service ? "service" : "application");

        List<String> using = new ArrayList<>();
        for (Kind kind : new Kind[] {Kind.DATABASE, Kind.POOL, Kind.LIBRARY, Kind.MESSAGING, Kind.CACHE, Kind.SEARCH, Kind.FRAMEWORK, Kind.SERVER}) {
            for (Tech t : techs) {
                if (t.kind() == kind && t != framework && using.size() < 6) {
                    using.add(t.name());
                }
            }
        }
        if (!using.isEmpty()) {
            sb.append(" using ").append(list(using));
        }
        List<String> on = new ArrayList<>();
        for (Tech t : techs) {
            if (t.kind() == Kind.PLATFORM || t.kind() == Kind.CLOUD) {
                on.add(t.name());
            }
        }
        if (!on.isEmpty()) {
            sb.append(", running on ").append(list(on));
        }
        return new Profile(sb.toString(), language, names);
    }

    /** The language the stack traces were written in; otherwise the one the framework implies. */
    private static Language language(AnalysisResult result, Map<String, Long> found) {
        Map<Language, Long> frames = new EnumMap<>(Language.class);
        for (ErrorGroup g : result.groups()) {
            if (g.language() != null && g.language() != Language.UNKNOWN) {
                frames.merge(g.language(), g.count(), Long::sum);
            }
        }
        if (!frames.isEmpty()) {
            return frames.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        }
        for (Tech t : CATALOGUE) {
            if (t.language() != null && found.containsKey(t.name()) && t.kind() == Kind.FRAMEWORK) {
                return t.language();
            }
        }
        return Language.UNKNOWN;
    }

    private static String languageName(Language language) {
        return switch (language) {
            case JAVA -> "Java";
            case DOTNET -> ".NET";
            case PYTHON -> "Python";
            case NODE -> "Node.js";
            case GO -> "Go";
            case RUBY -> "Ruby";
            case PHP -> "PHP";
            case RUST -> "Rust";
            case CPP -> "C/C++";
            case UNKNOWN -> null;
        };
    }

    private static boolean has(List<Tech> techs, String name) {
        return techs.stream().anyMatch(t -> t.name().equals(name));
    }

    private static String article(String word) {
        return "AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an" : "a";
    }

    private static String list(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
