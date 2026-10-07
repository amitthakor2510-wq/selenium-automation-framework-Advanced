package com.automation.core.api.spec;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a response against an {@code expect:} block and returns EVERY failure (not just the first),
 * so one run tells you everything that is wrong with a call.
 *
 * <pre>
 *   expect:
 *     status: 201                  200 | [200, 201] | "2xx" | "200-299"
 *     maxTimeMs: 2000              response must arrive within this many milliseconds
 *     contentType: json            Content-Type header contains this text (case-insensitive)
 *     headers:                     header name -> value / operator map (see ValueMatcher)
 *       Location: { startsWith: /posts/ }
 *     body:                        JSON path -> value / operator map
 *       id: notNull
 *       "items.size()": { gt: 0 }
 *     bodyContains: ["ok"]         raw text must contain each of these
 *     bodyNotContains: ["error"]
 *     emptyBody: true              (e.g. for 204 responses)
 *     xml:                         XML / SOAP: path -> value / operator map
 *       Envelope.Body.AddResponse.AddResult: 5
 *       "//*[local-name()='Fault']": absent
 * </pre>
 * A bare number ({@code expect: 200}) is shorthand for {@code expect: { status: 200 }}.
 * {@code schema:} / {@code schemaInline:} are handled by the runner (they need the JSON-schema library).
 */
public final class ExpectationChecker {

    private ExpectationChecker() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> check(Map<String, Object> expect, ResponseData response) {
        List<String> failures = new ArrayList<>();
        if (expect == null) {
            return failures;
        }

        Object status = expect.get("status");
        if (status != null) {
            String failure = checkStatus(status, response.status());
            if (failure != null) {
                failures.add(failure);
            }
        }

        Object maxTime = expect.get("maxTimeMs");
        if (maxTime instanceof Number n && response.timeMs() > n.longValue()) {
            failures.add("response time " + response.timeMs() + " ms exceeded the limit of " + n.longValue() + " ms");
        }

        Object contentType = expect.get("contentType");
        if (contentType != null) {
            String actual = response.header("Content-Type");
            if (actual == null || !actual.toLowerCase(Locale.ROOT).contains(String.valueOf(contentType).toLowerCase(Locale.ROOT))) {
                failures.add("Content-Type expected to contain \"" + contentType + "\" but was " + ValueMatcher.show(actual));
            }
        }

        Object headers = expect.get("headers");
        if (headers instanceof Map<?, ?> headerMap) {
            for (Map.Entry<?, ?> e : headerMap.entrySet()) {
                String name = String.valueOf(e.getKey());
                String actual = response.headerJoined(name);
                Object actualValue = actual == null ? JsonPathLite.MISSING : actual;
                String failure = ValueMatcher.check(e.getValue(), actualValue);
                if (failure != null) {
                    failures.add("header " + name + ": " + failure);
                }
            }
        }

        Object body = expect.get("body");
        if (body instanceof Map<?, ?> bodyMap && !bodyMap.isEmpty()) {
            Object json = response.json();
            if (json == null) {
                failures.add("body checks need a JSON response but the body was not JSON: " + ValueMatcher.show(response.body()));
            } else {
                for (Map.Entry<?, ?> e : bodyMap.entrySet()) {
                    String path = String.valueOf(e.getKey());
                    try {
                        String failure = ValueMatcher.check(e.getValue(), JsonPathLite.read(json, path));
                        if (failure != null) {
                            failures.add("body." + path + ": " + failure);
                        }
                    } catch (IllegalArgumentException ex) {
                        failures.add("body." + path + ": bad path - " + ex.getMessage());
                    }
                }
            }
        }

        for (String needle : asStrings(expect.get("bodyContains"))) {
            if (!response.body().contains(needle)) {
                failures.add("body does not contain \"" + needle + "\"");
            }
        }
        for (String needle : asStrings(expect.get("bodyNotContains"))) {
            if (response.body().contains(needle)) {
                failures.add("body unexpectedly contains \"" + needle + "\"");
            }
        }
        if (Boolean.TRUE.equals(expect.get("emptyBody")) && !response.body().isBlank()) {
            failures.add("expected an empty body but got " + ValueMatcher.show(response.body()));
        }

        Object xml = expect.get("xml");
        if (xml instanceof Map<?, ?> xmlMap && !xmlMap.isEmpty()) {
            for (Map.Entry<?, ?> e : xmlMap.entrySet()) {
                String path = String.valueOf(e.getKey());
                try {
                    Object actual = readXml(response.body(), path);
                    String failure = ValueMatcher.check(e.getValue(), actual);
                    if (failure != null) {
                        failures.add("xml." + path + ": " + failure);
                    }
                } catch (Exception ex) {
                    failures.add("xml." + path + ": " + ex.getMessage());
                }
            }
        }
        return failures;
    }

    static String checkStatus(Object expected, int actual) {
        if (expected instanceof Number n) {
            return n.intValue() == actual ? null : "expected HTTP " + n.intValue() + " but got HTTP " + actual;
        }
        if (expected instanceof List<?> list) {
            for (Object item : list) {
                if (checkStatus(item, actual) == null) {
                    return null;
                }
            }
            return "expected HTTP status in " + list + " but got HTTP " + actual;
        }
        String text = String.valueOf(expected).trim().toLowerCase(Locale.ROOT);
        if (text.equals("any")) {
            return null;
        }
        Matcher cls = Pattern.compile("([1-5])xx").matcher(text);
        if (cls.matches()) {
            return actual / 100 == Integer.parseInt(cls.group(1)) ? null : "expected HTTP " + text + " but got HTTP " + actual;
        }
        Matcher range = Pattern.compile("(\\d{3})\\s*-\\s*(\\d{3})").matcher(text);
        if (range.matches()) {
            return actual >= Integer.parseInt(range.group(1)) && actual <= Integer.parseInt(range.group(2)) ? null
                : "expected HTTP " + text + " but got HTTP " + actual;
        }
        try {
            return Integer.parseInt(text) == actual ? null : "expected HTTP " + text + " but got HTTP " + actual;
        } catch (NumberFormatException e) {
            return "unsupported status expectation: " + expected;
        }
    }

    private static List<String> asStrings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(v -> out.add(String.valueOf(v)));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    /**
     * Reads one value from an XML document. A path such as {@code Envelope.Body.AddResponse.AddResult}
     * is turned into a namespace-proof XPath; a path starting with "/" or "//" is used as written.
     * Returns the text of the node, a Double for XPath number() results, or MISSING.
     */
    public static Object readXml(String xml, String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

        String xpath = path.startsWith("/") || path.contains("(") ? path : dotPathToXPath(path);
        javax.xml.xpath.XPath engine = XPathFactory.newInstance().newXPath();
        org.w3c.dom.NodeList nodes;
        try {
            nodes = (org.w3c.dom.NodeList) engine.evaluate(xpath, doc, XPathConstants.NODESET);
        } catch (javax.xml.xpath.XPathExpressionException notANodeSet) {
            // count(...), string(...), boolean(...) style expressions return a value, not nodes
            return engine.evaluate(xpath, doc, XPathConstants.STRING);
        }
        if (nodes.getLength() == 0) {
            return JsonPathLite.MISSING;
        }
        if (nodes.getLength() == 1) {
            return nodes.item(0).getTextContent();
        }
        List<Object> all = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            all.add(nodes.item(i).getTextContent());
        }
        return all;
    }

    private static String dotPathToXPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("\\.")) {
            sb.append("/*[local-name()='").append(part).append("']");
        }
        return sb.toString();
    }
}
