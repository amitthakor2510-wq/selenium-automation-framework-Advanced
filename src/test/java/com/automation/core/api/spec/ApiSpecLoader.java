package com.automation.core.api.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Reads spec files into {@link SpecFile}s. Supported formats:
 * <ul>
 *   <li>{@code .yml / .yaml} - the recommended format (see docs/API_NO_CODE_GUIDE.md)</li>
 *   <li>{@code .json} - same structure as YAML</li>
 *   <li>{@code .csv} - one request per row, for people who live in spreadsheets</li>
 * </ul>
 */
public final class ApiSpecLoader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> VERBS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    private ApiSpecLoader() {
    }

    /** SafeConstructor that keeps 2024-01-31 style values as text instead of turning them into dates. */
    private static final class TextDatesConstructor extends SafeConstructor {
        TextDatesConstructor(LoaderOptions options) {
            super(options);
            this.yamlConstructors.put(Tag.TIMESTAMP, new ConstructYamlStr());
        }
    }

    /** True for the file types the loader understands. */
    public static boolean isSpecFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json") || n.endsWith(".csv");
    }

    /** All spec files directly inside {@code dir}, sorted by name. */
    public static List<Path> findSpecFiles(Path dir) throws IOException {
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).filter(ApiSpecLoader::isSpecFile)
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted().forEach(found::add);
            }
        }
        return found;
    }

    /**
     * @param environment name from the spec's {@code environments:} block (may be null/blank)
     * @param varOverrides extra variables that beat the file's own (e.g. {@code -Dapi.var.userId=5})
     */
    public static SpecFile load(Path file, String environment, Map<String, Object> varOverrides) throws IOException {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, Object> root;
        if (lower.endsWith(".csv")) {
            root = csvToSpec(Files.readString(file, StandardCharsets.UTF_8));
        } else if (lower.endsWith(".json")) {
            root = asMap(JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Object.class), file);
        } else {
            LoaderOptions options = new LoaderOptions();
            Object parsed = new Yaml(new TextDatesConstructor(options)).load(Files.readString(file, StandardCharsets.UTF_8));
            root = asMap(parsed, file);
        }
        return build(file, root, environment, varOverrides == null ? Map.of() : varOverrides);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed, Path file) {
        if (parsed instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (parsed instanceof List<?> list) {
            // A bare list of requests is allowed: treat it as "tests:"
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("tests", list);
            return wrapper;
        }
        throw new IllegalArgumentException(file + ": expected a YAML/JSON object with a 'tests:' list");
    }

    @SuppressWarnings("unchecked")
    static SpecFile build(Path file, Map<String, Object> root, String environment, Map<String, Object> varOverrides)
        throws IOException {
        Map<String, Object> settings = new LinkedHashMap<>(root);
        Object testsRaw = firstPresent(settings, "tests", "cases", "requests");
        settings.remove("tests");
        settings.remove("cases");
        settings.remove("requests");

        String baseName = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        SpecFile spec = new SpecFile(file, settings.containsKey("name") ? String.valueOf(settings.get("name")) : baseName, settings);

        // environments: pick one and layer its baseUrl / vars / headers on top of the file defaults
        Object envs = settings.get("environments");
        if (environment != null && !environment.isBlank()) {
            if (!(envs instanceof Map<?, ?> envMap) || !envMap.containsKey(environment)) {
                throw new IllegalArgumentException(file.getFileName() + ": environment '" + environment
                    + "' not found under 'environments:'" + (envs instanceof Map<?, ?> m ? " (available: " + m.keySet() + ")" : ""));
            }
            Map<String, Object> env = (Map<String, Object>) envMap.get(environment);
            if (env.containsKey("baseUrl")) {
                settings.put("baseUrl", env.get("baseUrl"));
            }
            if (env.get("headers") instanceof Map<?, ?> eh) {
                Map<String, Object> merged = new LinkedHashMap<>(spec.map("headers"));
                merged.putAll((Map<String, Object>) eh);
                settings.put("headers", merged);
            }
            if (env.get("vars") instanceof Map<?, ?> ev) {
                spec.vars().putAll((Map<String, Object>) ev);
            }
        }
        if (settings.get("vars") instanceof Map<?, ?> v) {
            ((Map<String, Object>) v).forEach(spec.vars()::putIfAbsent);
        }
        spec.vars().putAll(varOverrides);

        if (!(testsRaw instanceof List<?> tests)) {
            throw new IllegalArgumentException(file.getFileName() + ": no 'tests:' list found");
        }
        Function<String, String> noProps = k -> null;
        VariableResolver namer = new VariableResolver(noProps, file.getParent());
        int index = 0;
        for (Object item : tests) {
            index++;
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(file.getFileName() + ": test #" + index + " must be an object");
            }
            Map<String, Object> normalized = normalize((Map<String, Object>) itemMap, index);
            List<Map<String, Object>> rows = dataRows(normalized, file);
            String name = String.valueOf(normalized.get("name"));
            if (rows.isEmpty()) {
                spec.addCase(new ApiCase(spec, normalized, name, new LinkedHashMap<>()));
            } else {
                int r = 0;
                for (Map<String, Object> row : rows) {
                    r++;
                    Map<String, Object> scope = new LinkedHashMap<>(row);
                    scope.put("row", row);
                    String caseName;
                    try {
                        caseName = name.contains("${") ? namer.resolveText(name, scope) : name + " [" + r + "]";
                    } catch (VariableResolver.UnresolvedVariableException e) {
                        caseName = name + " [" + r + "]";
                    }
                    spec.addCase(new ApiCase(spec, normalized, caseName, row));
                }
            }
        }
        return spec;
    }

    private static Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    /** Accepts the friendly shorthands and returns the canonical form the runner reads. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> normalize(Map<String, Object> in, int index) {
        Map<String, Object> out = new LinkedHashMap<>(in);
        for (String verb : VERBS) {
            if (out.containsKey(verb) && !out.containsKey("method")) {
                out.put("method", verb.toUpperCase(Locale.ROOT));
                out.put("path", out.remove(verb));
                break;
            }
        }
        if (out.containsKey("assert") && !out.containsKey("expect")) {
            out.put("expect", out.remove("assert"));
        }
        Object expect = out.get("expect");
        if (expect != null && !(expect instanceof Map)) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("status", expect);
            out.put("expect", wrapped);
        } else if (expect == null) {
            out.put("expect", new LinkedHashMap<String, Object>());
        }
        if (!out.containsKey("name") || String.valueOf(out.get("name")).isBlank()) {
            String path = String.valueOf(out.containsKey("path") ? out.get("path") : out.getOrDefault("url", "#" + index));
            out.put("name", String.valueOf(out.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT) + " " + path);
        }
        return out;
    }

    /** Rows for a data-driven case: inline {@code data:} list, or {@code dataFile:} (csv / json / yml). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataRows(Map<String, Object> testCase, Path specFile) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object inline = testCase.get("data");
        if (inline instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    rows.add(new LinkedHashMap<>((Map<String, Object>) m));
                } else {
                    Map<String, Object> single = new LinkedHashMap<>();
                    single.put("value", o);
                    rows.add(single);
                }
            }
        }
        Object dataFile = testCase.get("dataFile");
        if (dataFile != null) {
            Path p = Path.of(String.valueOf(dataFile));
            if (!p.isAbsolute() && specFile.getParent() != null && Files.exists(specFile.getParent().resolve(p))) {
                p = specFile.getParent().resolve(p);
            }
            if (!Files.exists(p)) {
                throw new IllegalArgumentException(specFile.getFileName() + ": dataFile not found: " + p);
            }
            String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (lower.endsWith(".csv")) {
                List<List<String>> table = parseCsv(text);
                if (!table.isEmpty()) {
                    List<String> header = table.get(0);
                    for (int i = 1; i < table.size(); i++) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 0; c < header.size() && c < table.get(i).size(); c++) {
                            row.put(header.get(c).trim(), typed(table.get(i).get(c)));
                        }
                        rows.add(row);
                    }
                }
            } else {
                Object parsed = lower.endsWith(".json") ? JSON.readValue(text, Object.class)
                    : new Yaml(new TextDatesConstructor(new LoaderOptions())).load(text);
                if (parsed instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            rows.add(new LinkedHashMap<>((Map<String, Object>) m));
                        }
                    }
                }
            }
        }
        return rows;
    }

    // ---- CSV ---------------------------------------------------------------------------

    /**
     * CSV columns (header names are case-insensitive, all optional except path):
     * name, method, path, status, body, headers ("K:V;K2:V2"), query ("a=1&b=2"), maxTimeMs,
     * expectBody ("id=notNull;title=hello"), extract ("postId=id;tok=data.token"), tags ("smoke,crud").
     */
    static Map<String, Object> csvToSpec(String csv) {
        List<List<String>> table = parseCsv(csv);
        if (table.size() < 2) {
            throw new IllegalArgumentException("CSV spec needs a header row and at least one request row");
        }
        List<String> header = new ArrayList<>();
        table.get(0).forEach(h -> header.add(h.trim().toLowerCase(Locale.ROOT)));
        List<Object> tests = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String cell = c < cells.size() ? cells.get(c).trim() : "";
                if (!cell.isEmpty()) {
                    row.put(header.get(c), cell);
                }
            }
            if (row.isEmpty()) {
                continue;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            if (row.containsKey("name")) {
                t.put("name", row.get("name"));
            }
            t.put("method", row.getOrDefault("method", "GET").toUpperCase(Locale.ROOT));
            t.put("path", row.getOrDefault("path", row.getOrDefault("url", "")));
            if (row.containsKey("headers")) {
                t.put("headers", pairs(row.get("headers"), ":", ";"));
            }
            if (row.containsKey("query")) {
                t.put("query", pairs(row.get("query"), "=", "&"));
            }
            if (row.containsKey("body")) {
                String body = row.get("body");
                if (body.startsWith("{") || body.startsWith("[")) {
                    try {
                        t.put("body", JSON.readValue(body, Object.class));
                    } catch (IOException e) {
                        throw new IllegalArgumentException("CSV row " + (r + 1) + ": body is not valid JSON: " + e.getMessage());
                    }
                } else {
                    t.put("bodyText", body);
                }
            }
            Map<String, Object> expect = new LinkedHashMap<>();
            if (row.containsKey("status")) {
                expect.put("status", typed(row.get("status")));
            }
            if (row.containsKey("maxtimems")) {
                expect.put("maxTimeMs", typed(row.get("maxtimems")));
            }
            if (row.containsKey("expectbody")) {
                Map<String, Object> bodyChecks = new LinkedHashMap<>();
                pairs(row.get("expectbody"), "=", ";").forEach((k, v) -> bodyChecks.put(k, typed(String.valueOf(v))));
                expect.put("body", bodyChecks);
            }
            t.put("expect", expect);
            if (row.containsKey("extract")) {
                t.put("extract", pairs(row.get("extract"), "=", ";"));
            }
            if (row.containsKey("tags")) {
                t.put("tags", row.get("tags"));
            }
            tests.add(t);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tests", tests);
        return root;
    }

    private static Map<String, Object> pairs(String text, String kvSep, String pairSep) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String part : text.split(java.util.regex.Pattern.quote(pairSep))) {
            int i = part.indexOf(kvSep);
            if (i > 0) {
                out.put(part.substring(0, i).trim(), part.substring(i + kvSep.length()).trim());
            }
        }
        return out;
    }

    /** "42" -> 42, "true" -> true, anything else stays text. */
    static Object typed(String s) {
        String t = s.trim();
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(t);
        }
        if (t.matches("-?\\d{1,15}")) {
            return Long.parseLong(t) <= Integer.MAX_VALUE && Long.parseLong(t) >= Integer.MIN_VALUE
                ? (Object) Integer.parseInt(t) : (Object) Long.parseLong(t);
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(t);
        }
        return t;
    }

    /** Minimal RFC-4180 CSV parser (quotes, doubled quotes, commas and newlines inside quotes). */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String src = text.startsWith("\uFEFF") ? text.substring(1) : text;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < src.length() && src.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < src.length() && src.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
