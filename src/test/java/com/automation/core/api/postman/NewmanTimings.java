package com.automation.core.api.postman;

import com.automation.core.budget.PerfSamples;
import com.automation.core.budget.PerfSamples.Kind;
import com.automation.core.budget.PerfSamples.Sample;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the per-request timings of a Newman JSON report into performance-budget samples, so a
 * slow request inside a Postman collection fails the run exactly like a slow call made through
 * {@code ApiClient}. Newman runs as a separate process, so its requests never pass through the
 * RestAssured {@code ApiBudgetFilter}; this reads them from the report instead.
 *
 * <p>Each sample is named {@code "METHOD /path"} - the same form {@code ApiBudgetFilter} uses -
 * so the same {@code perf.budget.api.overrides} apply. Postman variables in the path are left as
 * written ({@code /users/{{id}}}); use a {@code *} wildcard in an override to cover them.
 */
public final class NewmanTimings {

    private NewmanTimings() {
    }

    /** Samples for every execution in {@code run} (the {@code "run"} node of Newman's JSON export). */
    public static List<Sample> samples(JsonNode run) {
        List<Sample> samples = new ArrayList<>();
        for (JsonNode execution : run.path("executions")) {
            JsonNode time = execution.path("response").path("responseTime");
            if (!time.isNumber()) {
                continue; // request failed before any response: nothing to time
            }
            samples.add(new Sample(Kind.API, name(execution.path("request")), time.asLong()));
        }
        return samples;
    }

    /** Records every execution's timing on the current thread (read by TestListener after the test). */
    public static void record(JsonNode run) {
        for (Sample sample : samples(run)) {
            PerfSamples.record(sample.kind(), sample.name(), sample.millis());
        }
    }

    static String name(JsonNode request) {
        String method = request.path("method").asText("GET").toUpperCase(java.util.Locale.ROOT);
        StringBuilder path = new StringBuilder();
        for (JsonNode segment : request.path("url").path("path")) {
            path.append('/').append(segment.asText());
        }
        return method + " " + (path.length() == 0 ? "/" : path.toString());
    }
}
