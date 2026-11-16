package io.logreader.parser;

import io.logreader.parser.FrameExtractor.RawFrame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Separates application code frames from framework, library and runtime noise. */
public final class FrameClassifier {

    private static final List<String> DEFAULT_MODULE_PREFIXES = List.of(
            // JVM
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.springframework.", "org.apache.",
            "org.hibernate.", "io.netty.", "kotlin.", "kotlinx.", "scala.", "akka.", "org.junit.", "reactor.",
            "io.reactivex.", "com.fasterxml.", "org.eclipse.jetty.", "com.zaxxer.", "org.postgresql.", "com.mysql.",
            "oracle.jdbc.", "io.micrometer.", "org.jboss.", "io.undertow.", "groovy.", "org.codehaus.", "org.gradle.",
            "com.google.common.", "com.google.gson.", "org.slf4j.", "ch.qos.logback.", "okhttp3.", "io.grpc.",
            "org.aspectj.", "net.sf.cglib.", "io.vertx.", "io.quarkus.", "io.micronaut.", "org.thymeleaf.",
            "com.mongodb.", "redis.clients.", "io.lettuce.", "org.mockito.", "feign.", "retrofit2.",
            "io.github.resilience4j.", "io.opentelemetry.", "io.sentry.", "com.amazonaws.", "software.amazon.", "com.azure.",
            "com.google.cloud.", "com.google.protobuf.", "org.yaml.", "org.jetbrains.", "org.reactivestreams.", "com.netflix.",
            "io.kubernetes.", "io.fabric8.", "org.flywaydb.", "liquibase.", "org.quartz.", "org.elasticsearch.", "co.elastic.",
            // .NET
            "System.", "Microsoft.", "Newtonsoft.", "NLog.", "Serilog.", "AutoMapper.", "MediatR.", "Npgsql.",
            "Dapper.", "Swashbuckle.", "Grpc.", "Polly.", "MySql.", "StackExchange.", "FluentValidation.",
            "Castle.", "Autofac.", "lambda_method", "Interop.",
            // Go runtime and standard library
            "runtime", "net/http", "net", "reflect", "sync", "testing", "syscall", "os", "io", "database/sql",
            "encoding/json", "internal/",
            // Rust
            "std::", "core::", "alloc::", "tokio::", "rust_begin_unwind", "__rust", "futures::", "hyper::");

    private static final List<String> PATH_MARKERS = List.of(
            "node_modules/", "node:", "site-packages/", "dist-packages/", "/lib/python", "<frozen", "<string>",
            "/usr/local/go/", "/go/pkg/mod/", "/libexec/src/", "/rustc/", ".cargo/registry", "/vendor/", "/gems/",
            "/lib/ruby/", "webpack/", "/usr/lib/", "/usr/include/", "/usr/share/dotnet/", "<anonymous>",
            "/_/src/libraries/", "/.m2/", "/.pyenv/", "/.rbenv/", "/.nvm/");

    private final List<String> modulePrefixes;

    public FrameClassifier() {
        this(List.of());
    }

    /** @param extraFrameworkPrefixes additional package / namespace prefixes to treat as framework code */
    public FrameClassifier(Collection<String> extraFrameworkPrefixes) {
        List<String> all = new ArrayList<>(DEFAULT_MODULE_PREFIXES);
        all.addAll(extraFrameworkPrefixes);
        this.modulePrefixes = List.copyOf(all);
    }

    public boolean isApplication(RawFrame frame) {
        return switch (frame.language()) {
            case GO -> !isLibraryPath(frame.file()) && !isGoStandardPackage(frame.module());
            case RUST -> !isLibraryPath(frame.file()) && !hasFrameworkPrefix(frame.function());
            case JAVA, DOTNET -> !hasFrameworkPrefix(frame.module()) && !isProxy(frame.module())
                    && !isLibraryPath(frame.file());
            default -> !isLibraryPath(frame.file());
        };
    }

    private boolean hasFrameworkPrefix(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : modulePrefixes) {
            if (prefix.endsWith(".") || prefix.endsWith("::") || prefix.endsWith("/") || prefix.contains("_")) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Go standard-library packages have no dot in their import path ({@code net/http} vs {@code github.com/x/y}). */
    private boolean isGoStandardPackage(String module) {
        if (module == null) {
            return false;
        }
        if (module.equals("main") || module.startsWith("main.")) {
            return false;
        }
        int slash = module.indexOf('/');
        String first = slash < 0 ? module : module.substring(0, slash);
        if (first.indexOf('.') >= 0) {
            return false; // domain-qualified import path: third-party or application module
        }
        for (String prefix : modulePrefixes) {
            if (module.equals(prefix) || module.startsWith(prefix + "/") || module.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProxy(String module) {
        return module != null && (module.contains("$$") || module.startsWith("jdk.proxy") || module.contains("$Proxy"));
    }

    private static boolean isLibraryPath(String file) {
        if (file == null) {
            return false;
        }
        String path = file.replace('\\', '/');
        if (path.startsWith("internal/") || path.startsWith("runtime/")) {
            return true;
        }
        for (String marker : PATH_MARKERS) {
            if (path.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
