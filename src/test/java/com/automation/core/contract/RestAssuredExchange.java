package com.automation.core.contract;

import com.automation.core.api.ApiClient;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends contract requests through {@link ApiClient}, so they use the active site's base URL and
 * show up in the Allure report, the API log and the performance budget like any other API call.
 * Requires {@code ApiClient.configure()} to have run (BaseApiTest does that).
 */
public final class RestAssuredExchange implements ContractVerifier.Exchange {

    @Override
    public ContractVerifier.ActualResponse send(Contract.Request request) {
        RequestSpecification spec = ApiClient.request();
        request.headers().forEach(spec::header);
        for (Map.Entry<String, List<String>> param : request.query().entrySet()) {
            spec.queryParam(param.getKey(), param.getValue().toArray());
        }
        if (request.body() != null && !request.body().isNull()) {
            boolean hasContentType = request.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase("content-type"));
            if (!hasContentType) {
                spec.contentType("application/json");
            }
            spec.body(request.body().isTextual() ? request.body().asText() : request.body().toString());
        }
        Response response = spec.request(request.method(), request.path());
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            headers.merge(header.getName().toLowerCase(Locale.ROOT), header.getValue(), (a, b) -> a + ", " + b);
        }
        return new ContractVerifier.ActualResponse(response.statusCode(), headers, response.asString());
    }
}
