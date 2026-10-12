package com.automation.core.api.postman;

import com.automation.core.budget.PerfSamples;
import com.automation.core.budget.PerfSamples.Kind;
import com.automation.core.budget.PerfSamples.Sample;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic test: a Newman JSON export becomes budget samples named like ApiClient's. */
class NewmanTimingsTest {

    private static final String RUN = "{\"executions\":["
        + "{\"item\":{\"name\":\"List books\"},\"request\":{\"method\":\"get\",\"url\":{\"path\":[\"BookStore\",\"v1\",\"Books\"]}},"
        + "\"response\":{\"code\":200,\"responseTime\":412}},"
        + "{\"item\":{\"name\":\"Login\"},\"request\":{\"method\":\"POST\",\"url\":{\"path\":[\"Account\",\"v1\",\"{{id}}\"]}},"
        + "\"response\":{\"code\":200,\"responseTime\":6200}},"
        + "{\"item\":{\"name\":\"Home\"},\"request\":{\"method\":\"GET\",\"url\":{}},\"response\":{\"responseTime\":9}},"
        + "{\"item\":{\"name\":\"Unreachable\"},\"request\":{\"method\":\"GET\",\"url\":{\"path\":[\"down\"]}}}"
        + "]}";

    @AfterEach
    void clean() {
        PerfSamples.clear();
    }

    private static JsonNode run() throws IOException {
        return new ObjectMapper().readTree(RUN);
    }

    @Test
    void everyExecutionWithAResponseBecomesAnApiSample() throws IOException {
        List<Sample> samples = NewmanTimings.samples(run());
        assertEquals(3, samples.size(), "the request with no response is skipped");
        assertEquals(new Sample(Kind.API, "GET /BookStore/v1/Books", 412), samples.get(0));
        assertEquals(new Sample(Kind.API, "POST /Account/v1/{{id}}", 6200), samples.get(1));
        assertEquals("GET /", samples.get(2).name(), "no path means the root");
    }

    @Test
    void recordFeedsTheThreadLocalCollectorTheBudgetReads() throws IOException {
        NewmanTimings.record(run());
        List<Sample> drained = PerfSamples.drain();
        assertEquals(3, drained.size());
        assertTrue(drained.stream().allMatch(s -> s.kind() == Kind.API));
    }
}
