package com.automation.core.api.spec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline unit tests for the no-code API runner's engine (no network, no browser): path reading,
 * variables, matchers, response checks, spec loading, OpenAPI generation and the report.
 */
class ApiSpecEngineTest {

    private static Object json(String text) throws IOException {
        return JsonPathLite.parse(text);
    }

    private static ResponseData response(int status, long ms, String body) {
        return new ResponseData(status, ms, Map.of("Content-Type", List.of("application/json; charset=utf-8")), body);
    }

    // ---- JsonPathLite --------------------------------------------------------------------

    @Test
    void jsonPath_readsNestedIndexedWildcardFilteredAndSizeValues() throws IOException {
        Object doc = json("{\"id\":7,\"user\":{\"name\":\"Asha\"},\"items\":[{\"id\":1,\"t\":\"a\"},{\"id\":2,\"t\":\"b\"}],\"nil\":null}");
        assertEquals(7, JsonPathLite.read(doc, "id"));
        assertEquals("Asha", JsonPathLite.read(doc, "$.user.name"));
        assertEquals("b", JsonPathLite.read(doc, "items[1].t"));
        assertEquals("b", JsonPathLite.read(doc, "items[-1].t"));
        assertEquals(List.of(1, 2), JsonPathLite.read(doc, "items[*].id"));
        assertEquals(List.of("b"), JsonPathLite.read(doc, "items[?id=2].t"));
        assertEquals(2, JsonPathLite.read(doc, "items.size()"));
        assertNull(JsonPathLite.read(doc, "nil"));
        assertEquals(JsonPathLite.MISSING, JsonPathLite.read(doc, "user.age"));
        assertEquals(JsonPathLite.MISSING, JsonPathLite.read(doc, "items[5]"));
    }

    @Test
    void jsonPath_supportsRootArraysAndQuotedKeys() throws IOException {
        assertEquals(3, JsonPathLite.read(json("[1,2,3]"), "$.size()"));
        assertEquals(2, JsonPathLite.read(json("[1,2,3]"), "$[1]"));
        assertEquals("v", JsonPathLite.read(json("{\"a.b\":\"v\"}"), "['a.b']"));
    }

    // ---- VariableResolver ----------------------------------------------------------------

    @Test
    void variables_substituteKeepTypesFallBackAndFailClearly() {
        VariableResolver r = new VariableResolver(k -> k.equals("my.key") ? "fromProp" : null, null);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("id", 42);
        vars.put("name", "Asha");
        vars.put("row", Map.of("email", "a@b.c"));

        assertEquals("/users/42", r.resolveText("/users/${id}", vars));
        assertEquals(42, r.resolve("${id}", vars));                       // whole-string placeholder keeps the number
        assertEquals("a@b.c", r.resolveText("${row.email}", vars));
        assertEquals("dflt", r.resolveText("${missing:-dflt}", vars));
        assertEquals("fromProp", r.resolveText("${prop:my.key}", vars));
        assertEquals(36, r.resolveText("${uuid}", vars).length());
        assertTrue(r.resolveText("${randomEmail}", vars).endsWith("@example.com"));
        int n = Integer.parseInt(r.resolveText("${randomInt:5:7}", vars));
        assertTrue(n >= 5 && n <= 7);
        assertEquals(Map.of("k", 42), r.resolve(Map.of("k", "${id}"), vars));

        VariableResolver.UnresolvedVariableException ex =
            assertThrows(VariableResolver.UnresolvedVariableException.class, () -> r.resolveText("${nope}", vars));
        assertEquals("nope", ex.variable());
    }

    // ---- ValueMatcher --------------------------------------------------------------------

    @Test
    void matcher_comparesValuesKeywordsAndOperators() {
        assertNull(ValueMatcher.check(5, 5.0));
        assertNull(ValueMatcher.check("5", 5));
        assertNull(ValueMatcher.check("notNull", "x"));
        assertNotNull(ValueMatcher.check("notNull", null));
        assertNull(ValueMatcher.check("absent", JsonPathLite.MISSING));
        assertNotNull(ValueMatcher.check("hello", "bye"));
        assertNotNull(ValueMatcher.check("hello", JsonPathLite.MISSING));
        assertNull(ValueMatcher.check(Map.of("gt", 0, "lte", 10), 7));
        assertNotNull(ValueMatcher.check(Map.of("gt", 10), 7));
        assertNull(ValueMatcher.check(Map.of("contains", "ell"), "hello"));
        assertNull(ValueMatcher.check(Map.of("matches", "^h.l+o$"), "hello"));
        assertNull(ValueMatcher.check(Map.of("type", "array", "size", 2), List.of(1, 2)));
        assertNull(ValueMatcher.check(Map.of("in", List.of("a", "b")), "b"));
        assertNull(ValueMatcher.check(Map.of("eq", "notNull"), "notNull"));  // escape hatch for the literal text
        assertNotNull(ValueMatcher.check(Map.of("bogus", 1), "x"));          // not an operator map -> plain equality, fails
        assertNull(ValueMatcher.check(List.of(1, 2), List.of(1.0, 2.0)));
    }

    // ---- ExpectationChecker --------------------------------------------------------------

    @Test
    void checker_passesGoodResponsesAndReportsEveryFailureTogether() {
        Map<String, Object> expect = new LinkedHashMap<>();
        expect.put("status", 201);
        expect.put("maxTimeMs", 500);
        expect.put("contentType", "json");
        expect.put("body", Map.of("id", "notNull", "title", "hello"));
        ResponseData good = response(201, 120, "{\"id\":9,\"title\":\"hello\"}");
        assertEquals(List.of(), ExpectationChecker.check(expect, good));

        ResponseData bad = response(500, 900, "{\"title\":\"bye\"}");
        List<String> failures = ExpectationChecker.check(expect, bad);
        assertEquals(4, failures.size(), failures.toString());   // status, time, id, title
    }

    @Test
    void checker_understandsStatusClassesRangesAndLists() {
        assertNull(ExpectationChecker.checkStatus("2xx", 204));
        assertNotNull(ExpectationChecker.checkStatus("2xx", 404));
        assertNull(ExpectationChecker.checkStatus("200-299", 201));
        assertNull(ExpectationChecker.checkStatus(List.of(401, 403), 403));
        assertNull(ExpectationChecker.checkStatus("any", 500));
    }

    @Test
    void checker_readsSoapXmlByDotPathAndRejectsNonJsonForBodyChecks() {
        String soap = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<AddResponse xmlns=\"http://tempuri.org/\"><AddResult>5</AddResult></AddResponse></s:Body></s:Envelope>";
        Map<String, Object> expect = Map.of("xml", Map.of("Envelope.Body.AddResponse.AddResult", 5));
        assertEquals(List.of(), ExpectationChecker.check(expect, new ResponseData(200, 1, Map.of(), soap)));
        assertEquals(1, ExpectationChecker.check(Map.of("body", Map.of("a", 1)), new ResponseData(200, 1, Map.of(), "<x/>")).size());
    }

    // ---- ApiSpecLoader -------------------------------------------------------------------

    private static Path write(Path dir, String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void loader_readsYamlShorthandsDataRowsEnvironmentsAndKeepsDatesAsText() throws IOException {
        Path dir = Files.createTempDirectory("apispec");
        Path file = write(dir, "demo.yml", String.join("\n",
            "name: Demo",
            "baseUrl: https://prod.example.com",
            "vars: { who: Asha }",
            "environments:",
            "  staging: { baseUrl: 'https://stg.example.com', vars: { who: Bhavna } }",
            "tests:",
            "  - get: /users/1",
            "    expect: 200",
            "  - name: create ${name}",
            "    post: /users",
            "    body: { name: '${name}', born: 2024-01-31 }",
            "    expect: { status: 201 }",
            "    data:",
            "      - { name: A }",
            "      - { name: B }"));

        SpecFile prod = ApiSpecLoader.load(file, null, Map.of());
        assertEquals(3, prod.cases().size());
        assertEquals("GET /users/1", prod.cases().get(0).name());
        assertEquals("GET", prod.cases().get(0).method());
        assertEquals(200, prod.cases().get(0).map("expect").get("status"));
        assertEquals("create A", prod.cases().get(1).name());
        assertEquals("POST", prod.cases().get(2).method());
        assertEquals("2024-01-31", ((Map<?, ?>) prod.cases().get(1).get("body")).get("born"));
        assertEquals("Asha", prod.vars().get("who"));

        SpecFile staging = ApiSpecLoader.load(file, "staging", Map.of("extra", "1"));
        assertEquals("https://stg.example.com", staging.string("baseUrl"));
        assertEquals("Bhavna", staging.vars().get("who"));
        assertEquals("1", staging.vars().get("extra"));

        assertThrows(IllegalArgumentException.class, () -> ApiSpecLoader.load(file, "nope", Map.of()));
    }

    @Test
    void loader_readsCsvSpecsAndCsvDataFiles() throws IOException {
        Path dir = Files.createTempDirectory("apispec");
        Path csv = write(dir, "quick.csv", String.join("\n",
            "name,method,path,status,body,headers,expectBody,extract",
            "list,GET,/posts,200,,,,",
            "create,POST,/posts,201,\"{\"\"title\"\":\"\"x\"\"}\",X-A:1;X-B:2,id=notNull;title=x,newId=id"));
        SpecFile spec = ApiSpecLoader.load(csv, null, Map.of());
        assertEquals(2, spec.cases().size());
        ApiCase create = spec.cases().get(1);
        assertEquals("POST", create.method());
        assertEquals(201, create.map("expect").get("status"));
        assertEquals("x", ((Map<?, ?>) create.get("body")).get("title"));
        assertEquals("2", create.map("headers").get("X-B"));
        assertEquals("notNull", ((Map<?, ?>) create.map("expect").get("body")).get("id"));
        assertTrue(spec.isExtractedSomewhere("newId"));

        write(dir, "rows.csv", "id,expected\n1,200\n2,404\n");
        Path yml = write(dir, "dd.yml", "tests:\n  - get: /items/${id}\n    expect: ${expected}\n    dataFile: rows.csv\n");
        SpecFile dd = ApiSpecLoader.load(yml, null, Map.of());
        assertEquals(2, dd.cases().size());
        assertEquals(2, dd.cases().get(1).row().get("id"));
    }

    // ---- OpenApiSpecGenerator ------------------------------------------------------------

    @Test
    void openApi_generatesRunnableSpecWithChainingAuthAndNegatives() throws IOException {
        String doc = String.join("\n",
            "openapi: 3.0.0",
            "info: { title: Pets }",
            "servers: [ { url: 'https://api.example.com/v1/' } ]",
            "components:",
            "  securitySchemes: { b: { type: http, scheme: bearer } }",
            "  schemas:",
            "    Pet:",
            "      type: object",
            "      required: [name]",
            "      properties: { id: { type: integer, readOnly: true }, name: { type: string, example: Rex }, born: { type: string, format: date } }",
            "security: [ { b: [] } ]",
            "paths:",
            "  /pets:",
            "    post:",
            "      operationId: createPet",
            "      requestBody: { content: { application/json: { schema: { $ref: '#/components/schemas/Pet' } } } }",
            "      responses: { '201': { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/Pet' } } } } }",
            "  /pets/{petId}:",
            "    get:",
            "      operationId: getPet",
            "      parameters: [ { name: petId, in: path, required: true, schema: { type: integer } } ]",
            "      responses: { '200': { description: ok } }",
            "    delete:",
            "      operationId: deletePet",
            "      parameters: [ { name: petId, in: path, required: true, schema: { type: integer } } ]",
            "      responses: { '204': { description: gone } }");
        Map<String, Object> parsed = OpenApiSpecGenerator.parse(doc, "test");
        OpenApiSpecGenerator.Result result = OpenApiSpecGenerator.generate(parsed, true);
        assertEquals(3, result.operations());
        assertTrue(result.yaml().contains("baseUrl: https://api.example.com/v1"), result.yaml());
        assertTrue(result.yaml().contains("${env:API_TOKEN}"));
        assertTrue(result.yaml().contains("pets_id: id"), "POST should extract the new id");
        assertTrue(result.yaml().contains("${pets_id:-1}"), "GET/DELETE should reuse it");
        assertTrue(result.yaml().contains("without credentials"));
        assertFalse(result.yaml().contains("id: 1\n      born"), "readOnly id must not be sent in the request body");

        Path file = Files.createTempDirectory("apispec").resolve("gen.yml");
        Files.writeString(file, result.yaml(), StandardCharsets.UTF_8);
        SpecFile loaded = ApiSpecLoader.load(file, null, Map.of());
        assertEquals("POST", loaded.cases().get(0).method());          // POST first, DELETE last
        assertEquals("DELETE", loaded.cases().get(loaded.cases().size() - 1).method());
        assertEquals("Rex", ((Map<?, ?>) loaded.cases().get(0).get("body")).get("name"));
    }

    // ---- ApiRunReport --------------------------------------------------------------------

    @Test
    void report_escapesHtmlMasksSecretsAndCountsOutcomes() throws IOException {
        ApiRunReport report = new ApiRunReport();
        report.add(new ApiRunReport.Entry("s", "<b>bad</b>", "GET", "http://x/y", 500, 12, ApiRunReport.Outcome.FAILED,
            List.of("expected 200 but got 500"), "GET http://x/y", "{\"token\":\"abc\"}"));
        report.add(new ApiRunReport.Entry("s", "ok", "GET", "http://x/z", 200, 5, ApiRunReport.Outcome.PASSED,
            List.of(), "", ""));
        assertEquals(1, report.count(ApiRunReport.Outcome.FAILED));
        assertFalse(report.allGreen());
        Path html = report.write(Files.createTempDirectory("apireport"), "T");
        String text = Files.readString(html);
        assertTrue(text.contains("&lt;b&gt;bad&lt;/b&gt;"));
        assertFalse(text.contains("<b>bad</b>"));
        assertEquals("{\"token\": \"***\"}", ApiRunReport.maskSecrets("{\"token\": \"abc\"}"));
        assertTrue(ApiRunReport.isSecretHeader("Authorization"));
        assertFalse(ApiRunReport.isSecretHeader("Accept"));
    }
}
