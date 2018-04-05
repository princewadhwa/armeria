package com.ctrlshift.server.composition;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.ctrlshift.commons.HttpStatus;
import com.ctrlshift.commons.Request;
import com.ctrlshift.commons.RequestContext;
import com.ctrlshift.commons.Response;
import com.ctrlshift.commons.metric.MeterIdPrefix;
import com.ctrlshift.commons.util.SafeCloseable;
import com.ctrlshift.server.HttpStatusException;
import com.ctrlshift.server.PathMapped;
import com.ctrlshift.server.PathMapping;
import com.ctrlshift.server.PathMappingContext;
import com.ctrlshift.server.Router;
import com.ctrlshift.server.Routers;
import com.ctrlshift.server.Server;
import com.ctrlshift.server.Service;
import com.ctrlshift.server.ServiceCallbackInvoker;
import com.ctrlshift.server.ServiceConfig;
import com.ctrlshift.server.ServiceRequestContext;
import com.ctrlshift.server.ServiceRequestContextWrapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A skeletal {@link Service} implementation that enables composing multiple {@link Service}s into one.
 * Extend this class to build your own composite {@link Service}. e.g.
 * <pre>{@code
 * public class MyService extends AbstractCompositeService<HttpRequest, HttpResponse> {
 *     public MyService() {
 *         super(CompositeServiceEntry.ofPrefix("/foo/", new FooService()),
 *               CompositeServiceEntry.ofPrefix("/bar/", new BarService()),
 *               CompositeServiceEntry.ofCatchAll(new OtherService()));
 *     }
 * }
 * }</pre>
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 *
 * @see AbstractCompositeServiceBuilder
 * @see CompositeServiceEntry
 */
public abstract class AbstractCompositeService<I extends Request, O extends Response> implements Service<I, O> {

    private final List<CompositeServiceEntry<I, O>> services;
    @Nullable
    private Server server;
    @Nullable
    private Router<Service<I, O>> router;

    /**
     * Creates a new instance with the specified {@link CompositeServiceEntry}s.
     */
    @SafeVarargs
    protected AbstractCompositeService(CompositeServiceEntry<I, O>... services) {
        this(Arrays.asList(requireNonNull(services, "services")));
    }

    /**
     * Creates a new instance with the specified {@link CompositeServiceEntry}s.
     */
    protected AbstractCompositeService(Iterable<CompositeServiceEntry<I, O>> services) {
        requireNonNull(services, "services");

        this.services = ImmutableList.copyOf(services);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        checkState(server == null, "cannot be added to more than one server");
        server = cfg.server();
        router = Routers.ofCompositeService(services);

        final MeterRegistry registry = server.meterRegistry();
        final MeterIdPrefix meterIdPrefix =
                new MeterIdPrefix("armeria.server.router.compositeServiceCache",
                                  "hostnamePattern", cfg.virtualHost().hostnamePattern(),
                                  "pathMapping", cfg.pathMapping().meterTag());

        router.registerMetrics(registry, meterIdPrefix);
        for (CompositeServiceEntry<I, O> e : services()) {
            ServiceCallbackInvoker.invokeServiceAdded(cfg, e.service());
        }
    }

    /**
     * Returns the list of {@link CompositeServiceEntry}s added to this composite {@link Service}.
     */
    protected List<CompositeServiceEntry<I, O>> services() {
        return services;
    }

    /**
     * Returns the {@code index}-th {@link Service} in this composite {@link Service}. The index of the
     * {@link Service} added first is {@code 0}, and so on.
     */
    @SuppressWarnings("unchecked")
    protected <T extends Service<I, O>> T serviceAt(int index) {
        return (T) services().get(index).service();
    }

    /**
     * Finds the {@link Service} whose {@link PathMapping} matches the {@code path}.
     *
     * @param mappingCtx a context to find the {@link Service}.
     *
     * @return the {@link Service} wrapped by {@link PathMapped} if there's a match.
     *         {@link PathMapped#empty()} if there's no match.
     */
    protected PathMapped<Service<I, O>> findService(PathMappingContext mappingCtx) {
        assert router != null;
        return router.find(mappingCtx);
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        final PathMappingContext mappingCtx = ctx.pathMappingContext();
        final PathMapped<Service<I, O>> mapped = findService(mappingCtx.overridePath(ctx.mappedPath()));
        if (!mapped.isPresent()) {
            throw HttpStatusException.of(HttpStatus.NOT_FOUND);
        }

        final Optional<String> childPrefix = mapped.mapping().prefix();
        if (childPrefix.isPresent()) {
            final PathMapping newMapping = PathMapping.ofPrefix(ctx.pathMapping().prefix().get() +
                                                                childPrefix.get().substring(1));

            final ServiceRequestContext newCtx = new CompositeServiceRequestContext(
                    ctx, newMapping, mapped.mappingResult().path());
            try (SafeCloseable ignored = RequestContext.push(newCtx, false)) {
                return mapped.value().serve(newCtx, req);
            }
        } else {
            return mapped.value().serve(ctx, req);
        }
    }

    private static final class CompositeServiceRequestContext extends ServiceRequestContextWrapper {

        private final PathMapping pathMapping;
        private final String mappedPath;

        CompositeServiceRequestContext(ServiceRequestContext delegate, PathMapping pathMapping,
                                       String mappedPath) {
            super(delegate);
            this.pathMapping = pathMapping;
            this.mappedPath = mappedPath;
        }

        @Override
        public ServiceRequestContext newDerivedContext() {
            return newDerivedContext(super.newDerivedContext());
        }

        @Override
        public ServiceRequestContext newDerivedContext(Request request) {
            return newDerivedContext(super.newDerivedContext(request));
        }

        private ServiceRequestContext newDerivedContext(ServiceRequestContext derivedCtx) {
            return new CompositeServiceRequestContext(derivedCtx, pathMapping, mappedPath);
        }

        @Override
        public PathMapping pathMapping() {
            return pathMapping;
        }

        @Override
        public String mappedPath() {
            return mappedPath;
        }
    }
}