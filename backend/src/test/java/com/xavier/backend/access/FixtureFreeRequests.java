package com.xavier.backend.access;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;

/**
 * Test-only, first filter of every test context (D171): MockMvc dispatches a request on the JUnit thread, so without
 * this the request (filter chain, controller, service) would inherit the fixture fallback and see every store. While a
 * request runs the thread is unmarked, so an actor-less request has production's unset scope.
 *
 * <p>Not covered: Boot registers this filter for REQUEST dispatches only, so a MockMvc {@code asyncDispatch(...)}
 * (the ASYNC dispatch of a completed async result) and a {@code MockMvcBuilders.standaloneSetup(...)} without this
 * filter run on the marked thread with the fixture fallback. Tests of those paths that need production semantics use
 * {@link StrictStoreScope} or {@link StoreScopeTestSupport#asFixture} the other way round (an explicit scope).
 */
public class FixtureFreeRequests implements Filter, Ordered {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        StoreScopeTestSupport.enter(false);
        try {
            chain.doFilter(request, response);
        } finally {
            StoreScopeTestSupport.leave();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
