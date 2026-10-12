package com.automation.core.budget;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.net.URI;

/**
 * RestAssured filter that records how long every API call took, so slow responses are checked
 * against the performance budget automatically - no test has to remember to call
 * {@code ApiAssertions.assertResponseTimeUnder}. It only RECORDS (see {@link PerfSamples});
 * {@code PerfBudgetEnforcer} decides pass/fail after the test. Registered once for every API
 * test by {@code ApiClient.configure()}.
 *
 * <p>The time is RestAssured's own {@code Response.getTime()}, i.e. request sent to response
 * received, independent of where this filter sits in the chain.
 */
public final class ApiBudgetFilter implements Filter {

    @Override
    public Response filter(FilterableRequestSpecification request, FilterableResponseSpecification response,
                           FilterContext ctx) {
        Response result = ctx.next(request, response);
        try {
            PerfSamples.record(PerfSamples.Kind.API, request.getMethod() + " " + pathOf(request), result.getTime());
        } catch (RuntimeException e) {
            // Measuring is best-effort: it must never turn a good response into an error.
        }
        return result;
    }

    private static String pathOf(FilterableRequestSpecification request) {
        try {
            String path = URI.create(request.getURI()).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (RuntimeException e) {
            return request.getDerivedPath();
        }
    }
}
