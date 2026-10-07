package com.automation.core.api.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an OpenAPI 3.x / Swagger 2.0 document into a ready-to-run spec file: one test per operation
 * with a sample request, the documented success status, required-field checks, auth wiring and
 * (optionally) negative tests. Point it at a URL ({@code /v3/api-docs}, {@code /swagger.json}) or a file.
 *
 * <p>The generated file is a normal spec - open it, adjust sample values, add checks - or just run it
 * as is. CLI: {@code OpenApiSpecGenerator <url-or-file> <out.yml> [--negative]}.
 */
public final class OpenApiSpecGenerator {

    /** Result of a generation run. */
    public record Result(String yaml, int operations, List<String> notes) {
    }

    private static final List<String> METHOD_ORDER = List.of("post", "get", "put", "patch", "delete", "head", "options");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Object> doc;
    private final boolean negative;
    private final List<String> notes = new ArrayList<>();
    private final boolean swagger2;

    private OpenApiSpecGenerator(Map<String, Object> doc, boolean negative) {
        this.doc = doc;
        this.negative = negative;
        this.swagger2 = doc.containsKey("swagger");
    }

    // ---- entry points ------------------------------------------------------------------

    /** Loads an OpenAPI/Swagger document from an http(s) URL or a file path. */
    public static Map<String, Object> load(String location) throws IOException, InterruptedException {
        String text;
        if (location.startsWith("http://") || location.startsWith("https://")) {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json, application/yaml, */*").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Could not download the OpenAPI document from " + location
                    + " - HTTP " + response.statusCode());
            }
            text = response.body();
        } else {
            text = Files.readString(Path.of(location), StandardCharsets.UTF_8);
        }
        return parse(text, location);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text, String origin) throws IOException {
        Object parsed = text.stripLeading().startsWith("{")
            ? JSON.readValue(text, Object.class)
            : new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (!(parsed instanceof Map<?, ?> map) || !(map.containsKey("openapi") || map.containsKey("swagger"))) {
            throw new IOException(origin + " does not look like an OpenAPI/Swagger document (no 'openapi' or 'swagger' key)");
        }
        return (Map<String, Object>) map;
    }

    public static Result generate(Map<String, Object> document, boolean includeNegative) {
        return new OpenApiSpecGenerator(document, includeNegative).run();
    }

    public static Result generateFromLocation(String location, boolean includeNegative) throws IOException, InterruptedException {
        return generate(load(location), includeNegative);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: OpenApiSpecGenerator <openapi-url-or-file> <out.yml> [--negative]");
            System.exit(2);
        }
        boolean neg = args.length > 2 && args[2].equals("--negative");
        Result r = generateFromLocation(args[0], neg);
        Path out = Path.of(args[1]);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, r.yaml(), StandardCharsets.UTF_8);
        System.out.println("Generated " + r.operations() + " operations -> " + out);
        r.notes().forEach(n -> System.out.println("  note: " + n));
    }

    // ---- generation --------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Result run() {
        Map<String, Object> spec = new LinkedHashMap<>();
        Map<String, Object> info = map(doc.get("info"));
        spec.put("name", String.valueOf(info.getOrDefault("title", "Generated API tests")));
        String baseUrl = detectBaseUrl();
        if (baseUrl != null) {
            spec.put("baseUrl", baseUrl);
        } else {
            notes.add("No absolute server URL in the document - pass -Dapi.base.url=https://your-host");
        }
        Map<String, Object> auth = detectAuth();
        if (auth != null) {
            spec.put("auth", auth);
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("maxTimeMs", 5000);
        spec.put("defaults", defaults);

        List<Object> tests = new ArrayList<>();
        Map<String, Object> paths = map(doc.get("paths"));
        List<String> pathKeys = new ArrayList<>(paths.keySet());
        Set<String> collectionsWithCreate = new LinkedHashSet<>();
        for (String p : pathKeys) {
            if (map(paths.get(p)).containsKey("post")) {
                collectionsWithCreate.add(p);
            }
        }

        int operations = 0;
        // POSTs first (they create data later calls can use), DELETEs last (they remove it)
        for (String method : METHOD_ORDER) {
            for (String path : pathKeys) {
                Map<String, Object> pathItem = map(paths.get(path));
                Object opRaw = pathItem.get(method);
                if (!(opRaw instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> op = (Map<String, Object>) opRaw;
                operations++;
                tests.add(buildCase(path, method, op, pathItem, collectionsWithCreate));
                if (negative) {
                    tests.addAll(buildNegativeCases(path, method, op, pathItem));
                }
            }
        }
        spec.put("tests", tests);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(120);
        options.setPrettyFlow(true);
        String header = "# Generated from the OpenAPI document - edit freely.\n"
            + "# Run:  ./Scripts/api-test.sh --spec <this file>   (see docs/API_NO_CODE_GUIDE.md)\n"
            + "# Sample values come from the document's examples; ${var:-fallback} lets you override any\n"
            + "# of them with  -Dapi.var.<var>=...  or by extracting a value from an earlier response.\n";
        return new Result(header + new Yaml(options).dump(spec), operations, notes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCase(String path, String method, Map<String, Object> op,
                                          Map<String, Object> pathItem, Set<String> collectionsWithCreate) {
        Map<String, Object> t = new LinkedHashMap<>();
        String label = firstNonBlank(str(op.get("operationId")), str(op.get("summary")), method.toUpperCase(Locale.ROOT) + " " + path);
        t.put("name", label);
        t.put("method", method.toUpperCase(Locale.ROOT));

        List<Map<String, Object>> params = new ArrayList<>();
        for (Object p : list(pathItem.get("parameters"))) {
            params.add(resolveRef(map(p)));
        }
        for (Object p : list(op.get("parameters"))) {
            params.add(resolveRef(map(p)));
        }

        String resolvedPath = path;
        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> headers = new LinkedHashMap<>();
        for (Map<String, Object> p : params) {
            String name = str(p.get("name"));
            String in = str(p.get("in"));
            Object sample = paramSample(p);
            if ("path".equals(in)) {
                String varName = pathVariableFor(path, name, collectionsWithCreate);
                resolvedPath = resolvedPath.replace("{" + name + "}", "${" + varName + ":-" + sample + "}");
            } else if ("query".equals(in) && Boolean.TRUE.equals(p.get("required"))) {
                query.put(name, sample);
            } else if ("header".equals(in) && Boolean.TRUE.equals(p.get("required"))
                && !name.equalsIgnoreCase("authorization")) {
                headers.put(name, sample);
            }
        }
        t.put("path", resolvedPath);
        if (!query.isEmpty()) {
            t.put("query", query);
        }
        if (!headers.isEmpty()) {
            t.put("headers", headers);
        }

        addBody(t, op, params);

        Map<String, Object> responses = map(op.get("responses"));
        String successCode = successCode(responses);
        Map<String, Object> expect = new LinkedHashMap<>();
        expect.put("status", successCode == null ? "2xx" : (Object) Integer.parseInt(successCode));
        Map<String, Object> responseSchema = successCode == null ? Map.of() : responseSchema(map(responses.get(successCode)));
        if (!responseSchema.isEmpty()) {
            expect.put("contentType", "json");
            Map<String, Object> bodyChecks = new LinkedHashMap<>();
            Map<String, Object> resolved = resolveSchema(responseSchema, 0);
            if ("array".equals(resolved.get("type"))) {
                bodyChecks.put("$", Map.of("type", "array"));
            } else {
                for (Object req : list(resolved.get("required"))) {
                    bodyChecks.put(String.valueOf(req), "exists");
                }
            }
            if (!bodyChecks.isEmpty()) {
                expect.put("body", bodyChecks);
            }
            if (method.equals("post") && map(resolved.get("properties")).containsKey("id")) {
                Map<String, Object> extract = new LinkedHashMap<>();
                extract.put(collectionVariable(path), "id");
                t.put("extract", extract);
            }
        }
        t.put("expect", expect);

        List<String> tags = new ArrayList<>();
        tags.add(method.equals("get") ? "read" : "write");
        for (Object tag : list(op.get("tags"))) {
            tags.add(String.valueOf(tag).toLowerCase(Locale.ROOT).replace(' ', '-'));
        }
        t.put("tags", tags);
        if (Boolean.TRUE.equals(op.get("deprecated"))) {
            t.put("skip", "operation is marked deprecated");
        }
        return t;
    }

    private List<Map<String, Object>> buildNegativeCases(String path, String method, Map<String, Object> op, Map<String, Object> pathItem) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> positive = buildCase(path, method, op, pathItem, Set.of());
        if (positive.containsKey("skip")) {
            return out;
        }
        if (requiresSecurity(op)) {
            Map<String, Object> t = new LinkedHashMap<>(positive);
            t.put("name", positive.get("name") + " - without credentials");
            t.put("auth", "none");
            t.remove("extract");
            t.put("expect", new LinkedHashMap<>(Map.of("status", List.of(401, 403))));
            t.put("tags", List.of("negative", "security"));
            out.add(t);
        }
        if (positive.containsKey("body") && Set.of("post", "put", "patch").contains(method)) {
            Map<String, Object> t = new LinkedHashMap<>(positive);
            t.put("name", positive.get("name") + " - empty body is rejected");
            t.put("body", new LinkedHashMap<String, Object>());
            t.remove("extract");
            t.put("expect", new LinkedHashMap<>(Map.of("status", "4xx")));
            t.put("tags", List.of("negative", "validation"));
            out.add(t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void addBody(Map<String, Object> t, Map<String, Object> op, List<Map<String, Object>> params) {
        if (swagger2) {
            for (Map<String, Object> p : params) {
                if ("body".equals(p.get("in"))) {
                    t.put("body", sample(map(p.get("schema")), 0));
                    return;
                }
            }
            Map<String, Object> form = new LinkedHashMap<>();
            for (Map<String, Object> p : params) {
                if ("formData".equals(p.get("in"))) {
                    if ("file".equals(p.get("type"))) {
                        t.put("skip", "needs a file upload - set 'multipart:' by hand");
                    }
                    form.put(str(p.get("name")), paramSample(p));
                }
            }
            if (!form.isEmpty()) {
                t.put("form", form);
            }
            return;
        }
        Map<String, Object> requestBody = resolveRef(map(op.get("requestBody")));
        Map<String, Object> content = map(requestBody.get("content"));
        if (content.isEmpty()) {
            return;
        }
        if (content.containsKey("application/json")) {
            t.put("body", mediaSample(map(content.get("application/json"))));
        } else if (content.containsKey("application/x-www-form-urlencoded")) {
            Object s = mediaSample(map(content.get("application/x-www-form-urlencoded")));
            t.put("form", s instanceof Map<?, ?> ? s : new LinkedHashMap<>());
        } else if (content.containsKey("multipart/form-data")) {
            t.put("skip", "needs a file upload - set 'multipart:' by hand (see docs/API_NO_CODE_GUIDE.md)");
        } else {
            String type = content.keySet().iterator().next();
            t.put("contentType", type);
            Object s = mediaSample(map(content.get(type)));
            t.put("bodyText", s == null ? "" : String.valueOf(s));
            notes.add(str(op.get("operationId")) + ": sent as raw " + type);
        }
    }

    private Object mediaSample(Map<String, Object> media) {
        if (media.containsKey("example")) {
            return media.get("example");
        }
        Map<String, Object> examples = map(media.get("examples"));
        if (!examples.isEmpty()) {
            Map<String, Object> first = map(examples.values().iterator().next());
            if (first.containsKey("value")) {
                return first.get("value");
            }
        }
        return sample(map(media.get("schema")), 0);
    }

    // ---- server / auth -----------------------------------------------------------------

    private String detectBaseUrl() {
        if (swagger2) {
            String host = str(doc.get("host"));
            if (host == null) {
                return null;
            }
            List<?> schemes = list(doc.get("schemes"));
            String scheme = schemes.isEmpty() ? "https" : String.valueOf(schemes.contains("https") ? "https" : schemes.get(0));
            String basePath = firstNonBlank(str(doc.get("basePath")), "");
            return scheme + "://" + host + ("/".equals(basePath) ? "" : basePath);
        }
        List<?> servers = list(doc.get("servers"));
        if (servers.isEmpty()) {
            return null;
        }
        String url = str(map(servers.get(0)).get("url"));
        if (url == null || !url.startsWith("http")) {
            return null;
        }
        for (Map.Entry<String, Object> v : map(map(servers.get(0)).get("variables")).entrySet()) {
            Object def = map(v.getValue()).get("default");
            if (def != null) {
                url = url.replace("{" + v.getKey() + "}", String.valueOf(def));
            }
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Map<String, Object> detectAuth() {
        Map<String, Object> schemes = swagger2 ? map(doc.get("securityDefinitions"))
            : map(map(doc.get("components")).get("securitySchemes"));
        if (schemes.isEmpty()) {
            return null;
        }
        Map<String, Object> scheme = map(schemes.values().iterator().next());
        String type = String.valueOf(scheme.get("type"));
        Map<String, Object> auth = new LinkedHashMap<>();
        if (type.equals("apiKey")) {
            auth.put("type", "apiKey");
            auth.put("name", scheme.get("name"));
            auth.put("in", scheme.getOrDefault("in", "header"));
            auth.put("value", "${env:API_KEY}");
        } else if (type.equals("http") && "basic".equalsIgnoreCase(str(scheme.get("scheme"))) || type.equals("basic")) {
            auth.put("type", "basic");
            auth.put("username", "${env:API_USER}");
            auth.put("password", "${env:API_PASSWORD}");
        } else {
            auth.put("type", "bearer");
            auth.put("token", "${env:API_TOKEN}");
        }
        notes.add("Auth scheme '" + type + "' detected - set the matching environment variable (or edit 'auth:' in the spec)");
        return auth;
    }

    private boolean requiresSecurity(Map<String, Object> op) {
        Object own = op.get("security");
        if (own instanceof List<?> l) {
            return !l.isEmpty() && !(l.size() == 1 && map(l.get(0)).isEmpty());
        }
        return !list(doc.get("security")).isEmpty();
    }

    // ---- schema sampling ---------------------------------------------------------------

    private Object paramSample(Map<String, Object> p) {
        if (p.containsKey("example")) {
            return p.get("example");
        }
        if (p.containsKey("default")) {
            return p.get("default");
        }
        Map<String, Object> schema = p.containsKey("schema") ? map(p.get("schema")) : p;
        Object s = sample(schema, 0);
        return s == null ? "1" : s;
    }

    private Object sample(Map<String, Object> rawSchema, int depth) {
        Map<String, Object> schema = resolveSchema(rawSchema, depth);
        if (depth > 5) {
            return null;
        }
        if (schema.containsKey("example")) {
            return schema.get("example");
        }
        if (schema.containsKey("default")) {
            return schema.get("default");
        }
        List<?> enumValues = list(schema.get("enum"));
        if (!enumValues.isEmpty()) {
            return enumValues.get(0);
        }
        String type = str(schema.get("type"));
        if (type == null && schema.containsKey("properties")) {
            type = "object";
        }
        if (type == null) {
            return "string";
        }
        String format = str(schema.get("format"));
        switch (type) {
            case "object":
                return sampleObject(schema, depth);
            case "array":
                return sampleArray(schema, depth);
            case "integer":
                return schema.get("minimum") instanceof Number n ? n.intValue() : 1;
            case "number":
                return schema.get("minimum") instanceof Number n ? n.doubleValue() : 1.5;
            case "boolean":
                return Boolean.TRUE;
            default:
                if (format == null) {
                    return "string";
                }
                switch (format) {
                    case "date":
                        return "2024-01-15";
                    case "date-time":
                        return "2024-01-15T10:30:00Z";
                    case "email":
                        return "qa.user@example.com";
                    case "uuid":
                        return "123e4567-e89b-12d3-a456-426614174000";
                    case "uri":
                    case "url":
                        return "https://example.com";
                    default:
                        return "string";
                }
        }
    }

    private Object sampleObject(Map<String, Object> schema, int depth) {
        Map<String, Object> obj = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(schema.get("properties")).entrySet()) {
            Map<String, Object> prop = resolveSchema(map(e.getValue()), depth + 1);
            if (Boolean.TRUE.equals(prop.get("readOnly"))) {
                continue;
            }
            obj.put(e.getKey(), sample(map(e.getValue()), depth + 1));
        }
        return obj;
    }

    private Object sampleArray(Map<String, Object> schema, int depth) {
        List<Object> arr = new ArrayList<>();
        arr.add(sample(map(schema.get("items")), depth + 1));
        return arr;
    }

    /** Follows $ref and flattens allOf / oneOf / anyOf (first branch) into one schema map. */
    private Map<String, Object> resolveSchema(Map<String, Object> schema, int depth) {
        Map<String, Object> current = resolveRef(schema);
        if (depth > 8) {
            return current;
        }
        if (current.get("allOf") instanceof List<?> all) {
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> props = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (Object part : all) {
                Map<String, Object> r = resolveSchema(map(part), depth + 1);
                merged.putAll(r);
                props.putAll(map(r.get("properties")));
                required.addAll(list(r.get("required")));
            }
            merged.put("properties", props);
            merged.put("required", required);
            merged.putAll(withoutAllOf(current));
            return merged;
        }
        for (String key : List.of("oneOf", "anyOf")) {
            if (current.get(key) instanceof List<?> options && !options.isEmpty()) {
                return resolveSchema(map(options.get(0)), depth + 1);
            }
        }
        return current;
    }

    private static Map<String, Object> withoutAllOf(Map<String, Object> m) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.remove("allOf");
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRef(Map<String, Object> node) {
        Map<String, Object> current = node;
        for (int hops = 0; hops < 10 && current.get("$ref") instanceof String ref; hops++) {
            if (!ref.startsWith("#/")) {
                notes.add("External $ref not followed: " + ref);
                return new LinkedHashMap<>();
            }
            Object target = doc;
            for (String part : ref.substring(2).split("/")) {
                target = target instanceof Map<?, ?> m ? m.get(part.replace("~1", "/").replace("~0", "~")) : null;
            }
            current = target instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseSchema(Map<String, Object> response) {
        Map<String, Object> resolved = resolveRef(response);
        if (swagger2) {
            return map(resolved.get("schema"));
        }
        Map<String, Object> json = map(map(resolved.get("content")).get("application/json"));
        return map(json.get("schema"));
    }

    private static String successCode(Map<String, Object> responses) {
        return responses.keySet().stream().filter(k -> k.matches("2\\d\\d")).min(Comparator.naturalOrder()).orElse(null);
    }

    // ---- naming ------------------------------------------------------------------------

    /** "/pets" -> "pets_id"; "/v1/store/orders" -> "orders_id". */
    private static String collectionVariable(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank() && !parts[i].startsWith("{")) {
                return parts[i].replaceAll("[^A-Za-z0-9]", "_") + "_id";
            }
        }
        return "created_id";
    }

    private static String pathVariableFor(String path, String paramName, Set<String> collectionsWithCreate) {
        int brace = path.indexOf("{" + paramName + "}");
        String parent = brace > 0 ? path.substring(0, brace) : "";
        String collection = parent.endsWith("/") ? parent.substring(0, parent.length() - 1) : parent;
        if (collectionsWithCreate.contains(collection) && path.indexOf('{') == brace && path.indexOf('{', brace + 1) < 0) {
            return collectionVariable(collection);
        }
        return paramName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    // ---- tiny utils --------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
