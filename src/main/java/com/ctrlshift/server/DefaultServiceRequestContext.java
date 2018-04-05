package com.ctrlshift.server;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrlshift.commons.HttpRequest;
import com.ctrlshift.commons.HttpResponse;
import com.ctrlshift.commons.MediaType;
import com.ctrlshift.commons.NonWrappingRequestContext;
import com.ctrlshift.commons.Request;
import com.ctrlshift.commons.SessionProtocol;
import com.ctrlshift.commons.logging.DefaultRequestLog;
import com.ctrlshift.commons.logging.RequestLog;
import com.ctrlshift.commons.logging.RequestLogBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.Attribute;

/**
 * Default {@link ServiceRequestContext} implementation.
 */
public class DefaultServiceRequestContext extends NonWrappingRequestContext implements ServiceRequestContext {

    private final Channel ch;
    private final ServiceConfig cfg;
    private final PathMappingContext pathMappingContext;
    private final PathMappingResult pathMappingResult;
    @Nullable
    private final SSLSession sslSession;

    private final DefaultRequestLog log;
    private final Logger logger;

    @Nullable
    private ExecutorService blockingTaskExecutor;

    private long requestTimeoutMillis;
    @Nullable
    private Runnable requestTimeoutHandler;
    private long maxRequestLength;
    @Nullable
    private volatile RequestTimeoutChangeListener requestTimeoutChangeListener;

    @Nullable
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param ch the {@link Channel} that handles the invocation
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     * @param sslSession the {@link SSLSession} for this invocation if it is over TLS
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            PathMappingContext pathMappingContext, PathMappingResult pathMappingResult, Request request,
            @Nullable SSLSession sslSession) {

        super(meterRegistry, sessionProtocol,
              requireNonNull(pathMappingContext, "pathMappingContext").method(), pathMappingContext.path(),
              requireNonNull(pathMappingResult, "pathMappingResult").query(),
              request);

        this.ch = requireNonNull(ch, "ch");
        this.cfg = requireNonNull(cfg, "cfg");
        this.pathMappingContext = pathMappingContext;
        this.pathMappingResult = pathMappingResult;
        this.sslSession = sslSession;

        log = new DefaultRequestLog(this);
        log.startRequest(ch, sessionProtocol, cfg.virtualHost().defaultHostname());
        logger = newLogger(cfg);

        final ServerConfig serverCfg = cfg.server().config();
        requestTimeoutMillis = serverCfg.defaultRequestTimeoutMillis();
        maxRequestLength = serverCfg.defaultMaxRequestLength();
    }

    private RequestContextAwareLogger newLogger(ServiceConfig cfg) {
        String loggerName = cfg.loggerName().orElse(null);
        if (loggerName == null) {
            loggerName = cfg.pathMapping().loggerName();
        }

        return new RequestContextAwareLogger(this, LoggerFactory.getLogger(
                cfg.server().config().serviceLoggerPrefix() + '.' + loggerName));
    }

    @Override
    public ServiceRequestContext newDerivedContext() {
        return newDerivedContext(request());
    }

    @Override
    public ServiceRequestContext newDerivedContext(Request request) {
        final DefaultServiceRequestContext ctx = new DefaultServiceRequestContext(
                cfg, ch, meterRegistry(), sessionProtocol(), pathMappingContext,
                pathMappingResult, request, sslSession());
        for (final Iterator<Attribute<?>> i = attrs(); i.hasNext();) {
            ctx.addAttr(i.next());
        }
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private <T> void addAttr(Attribute<?> attribute) {
        final Attribute<T> a = (Attribute<T>) attribute;
        attr(a.key()).set(a.get());
    }

    @Override
    protected Channel channel() {
        return ch;
    }

    @Override
    public Server server() {
        return cfg.server();
    }

    @Override
    public VirtualHost virtualHost() {
        return cfg.virtualHost();
    }

    @Override
    public PathMapping pathMapping() {
        return cfg.pathMapping();
    }

    @Override
    public PathMappingContext pathMappingContext() {
        return pathMappingContext;
    }

    @Override
    public Map<String, String> pathParams() {
        return pathMappingResult.pathParams();
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>> T service() {
        return cfg.service();
    }

    @Override
    public ExecutorService blockingTaskExecutor() {
        if (blockingTaskExecutor != null) {
            return blockingTaskExecutor;
        }

        return blockingTaskExecutor = makeContextAware(server().config().blockingTaskExecutor());
    }

    @Override
    public String mappedPath() {
        return pathMappingResult.path();
    }

    @Nullable
    @Override
    public MediaType negotiatedProduceType() {
        return pathMappingResult.negotiatedProduceType();
    }

    @Override
    public EventLoop eventLoop() {
        return ch.eventLoop();
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        return sslSession;
    }

    @Override
    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    @Override
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        if (requestTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMillis: " + requestTimeoutMillis + " (expected: >= 0)");
        }
        if (this.requestTimeoutMillis != requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis;
            final RequestTimeoutChangeListener listener = requestTimeoutChangeListener;
            if (listener != null) {
                if (ch.eventLoop().inEventLoop()) {
                    listener.onRequestTimeoutChange(requestTimeoutMillis);
                } else {
                    ch.eventLoop().execute(() -> listener.onRequestTimeoutChange(requestTimeoutMillis));
                }
            }
        }
    }

    @Override
    public void setRequestTimeout(Duration requestTimeout) {
        setRequestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Nullable
    public Runnable requestTimeoutHandler() {
        return requestTimeoutHandler;
    }

    @Override
    public void setRequestTimeoutHandler(Runnable requestTimeoutHandler) {
        this.requestTimeoutHandler = requireNonNull(requestTimeoutHandler, "requestTimeoutHandler");
    }

    @Override
    public long maxRequestLength() {
        return maxRequestLength;
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        if (maxRequestLength < 0) {
            throw new IllegalArgumentException(
                    "maxRequestLength: " + maxRequestLength + " (expected: >= 0)");
        }
        this.maxRequestLength = maxRequestLength;
    }

    @Override
    public RequestLog log() {
        return log;
    }

    @Override
    public RequestLogBuilder logBuilder() {
        return log;
    }

    @Override
    public ByteBufAllocator alloc() {
        return ch.alloc();
    }

    /**
     * Sets the listener that is notified when the {@linkplain #requestTimeoutMillis()} request timeout} of
     * the request is changed.
     *
     * <p>Note: This method is meant for internal use by server-side protocol implementation to reschedule
     * a timeout task when a user updates the request timeout configuration.
     */
    public void setRequestTimeoutChangeListener(RequestTimeoutChangeListener listener) {
        requireNonNull(listener, "listener");
        if (requestTimeoutChangeListener != null) {
            throw new IllegalStateException("requestTimeoutChangeListener is set already.");
        }
        requestTimeoutChangeListener = listener;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal != null) {
            return strVal;
        }

        final StringBuilder buf = new StringBuilder(96);

        // Prepend the current channel information if available.
        final Channel ch = channel();
        final boolean hasChannel = ch != null;
        if (hasChannel) {
            buf.append(ch);
        }

        buf.append('[')
           .append(sessionProtocol().uriText())
           .append("://")
           .append(virtualHost().defaultHostname());

        final InetSocketAddress laddr = localAddress();
        if (laddr != null) {
            buf.append(':').append(laddr.getPort());
        } else {
            buf.append(":-1"); // Port unknown.
        }

        buf.append(path())
           .append('#')
           .append(method())
           .append(']');

        strVal = buf.toString();
        if (hasChannel) {
            this.strVal = strVal;
        }
        return strVal;
    }
}