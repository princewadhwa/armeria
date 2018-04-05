package com.ctrlshift.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.ctrlshift.commons.HttpRequest;
import com.ctrlshift.commons.HttpResponse;

/**
 * A {@link Service} and its {@link PathMapping} and {@link VirtualHost}.
 *
 * @see ServerConfig#serviceConfigs()
 * @see VirtualHost#serviceConfigs()
 */
public final class ServiceConfig {

    private static final Pattern LOGGER_NAME_PATTERN =
            Pattern.compile("^\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*" +
                            "(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*$");

    /**
     * Initialized later by {@link VirtualHost} via {@link #build(VirtualHost)}.
     */
    @Nullable
    private VirtualHost virtualHost;

    private final PathMapping pathMapping;
    @Nullable
    private final String loggerName;
    private final Service<HttpRequest, HttpResponse> service;

    /**
     * Creates a new instance.
     */
    public ServiceConfig(VirtualHost virtualHost, PathMapping pathMapping,
                         Service<HttpRequest, HttpResponse> service) {
        this(virtualHost, pathMapping, service, null);
    }

    /**
     * Creates a new instance.
     */
    public ServiceConfig(VirtualHost virtualHost, PathMapping pathMapping,
                         Service<HttpRequest, HttpResponse> service,
                         @Nullable String loggerName) {
        this(pathMapping, service, loggerName);
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
    }

    /**
     * Creates a new instance.
     */
    ServiceConfig(PathMapping pathMapping, Service<HttpRequest, HttpResponse> service,
                  @Nullable String loggerName) {
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.service = requireNonNull(service, "service");
        this.loggerName = loggerName != null ? validateLoggerName(loggerName, "loggerName") : null;
    }

    static String validateLoggerName(String value, String propertyName) {
        requireNonNull(value, propertyName);
        if (!LOGGER_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(propertyName + ": " + value);
        }
        return value;
    }

    ServiceConfig build(VirtualHost virtualHost) {
        requireNonNull(virtualHost, "virtualHost");
        return new ServiceConfig(virtualHost, pathMapping(), service());
    }

    /**
     * Returns the {@link VirtualHost} the {@link #service()} belongs to.
     */
    public VirtualHost virtualHost() {
        if (virtualHost == null) {
            throw new IllegalStateException("Server has not been configured yet.");
        }
        return virtualHost;
    }

    /**
     * Returns the {@link Server} the {@link #service()} belongs to.
     */
    public Server server() {
        return virtualHost().server();
    }

    /**
     * Returns the {@link PathMapping} of the {@link #service()}.
     */
    public PathMapping pathMapping() {
        return pathMapping;
    }

    /**
     * Returns the {@link Service}.
     */
    @SuppressWarnings("unchecked")
    public <T extends Service<HttpRequest, HttpResponse>> T service() {
        return (T) service;
    }

    /**
     * Returns the logger name for the {@link Service}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    public Optional<String> loggerName() {
        return Optional.ofNullable(loggerName);
    }

    @Override
    public String toString() {
        if (virtualHost != null) {
            return virtualHost.hostnamePattern() + ": " + pathMapping + " -> " + service;
        } else {
            return pathMapping + " -> " + service;
        }
    }
}