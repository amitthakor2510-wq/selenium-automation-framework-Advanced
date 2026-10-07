package com.automation.core.api.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What the checker needs to know about an HTTP response, independent of the HTTP library used. */
public final class ResponseData {

    private final int status;
    private final long timeMs;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final String body;
    private Object parsedJson;
    private boolean jsonTried;

    public ResponseData(int status, long timeMs, Map<String, List<String>> headers, String body) {
        this.status = status;
        this.timeMs = timeMs;
        if (headers != null) {
            this.headers.putAll(headers);
        }
        this.body = body == null ? "" : body;
    }

    public int status() {
        return status;
    }

    public long timeMs() {
        return timeMs;
    }

    public String body() {
        return body;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    /** First value of a header, or null. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** All values of a header joined with ", " (null when absent). */
    public String headerJoined(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : String.join(", ", new ArrayList<>(values));
    }

    /** The body parsed as JSON, or null when it is not valid JSON. */
    public synchronized Object json() {
        if (!jsonTried) {
            jsonTried = true;
            try {
                parsedJson = body.isBlank() ? null : JsonPathLite.parse(body);
            } catch (Exception e) {
                parsedJson = null;
            }
        }
        return parsedJson;
    }

    public boolean isJson() {
        return json() != null;
    }
}
