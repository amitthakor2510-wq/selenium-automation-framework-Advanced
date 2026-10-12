package com.automation.core.contract;

import com.automation.core.contract.Contract.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests of the body comparison and Pact matching rules. */
class BodyMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, List<Rule>> NO_RULES = Map.of();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Rule rule(String match) {
        return new Rule(match, null, null, null, null);
    }

    private static List<String> match(String expected, String actual, Map<String, List<Rule>> rules) {
        return BodyMatcher.match(json(expected), json(actual), rules);
    }

    @Test
    void equalBodiesMatchAndExtraProviderFieldsAreAllowed() {
        assertEquals(List.of(), match("{\"id\":1,\"name\":\"a\"}", "{\"id\":1,\"name\":\"a\"}", NO_RULES));
        assertEquals(List.of(), match("{\"id\":1}", "{\"id\":1,\"name\":\"a\",\"extra\":[1,2]}", NO_RULES),
            "a consumer only depends on the fields it lists");
    }

    @Test
    void missingFieldWrongValueAndWrongShapeAreReportedWithTheirPath() {
        List<String> mismatches = match("{\"id\":1,\"user\":{\"name\":\"a\"},\"tags\":[\"x\"]}",
            "{\"user\":{\"name\":\"b\"},\"tags\":{}}", NO_RULES);
        assertEquals(3, mismatches.size(), mismatches.toString());
        assertTrue(mismatches.contains("$.id: missing from the response"), mismatches.toString());
        assertTrue(mismatches.contains("$.user.name: expected \"a\" but was \"b\""), mismatches.toString());
        assertTrue(mismatches.contains("$.tags: expected an array but was object"), mismatches.toString());
    }

    @Test
    void numbersCompareByValueNotByRepresentation() {
        assertEquals(List.of(), match("{\"price\":5}", "{\"price\":5.0}", NO_RULES));
        assertEquals(1, match("{\"price\":5}", "{\"price\":5.5}", NO_RULES).size());
    }

    @Test
    void arraysWithoutRulesMustMatchLengthAndElements() {
        assertEquals(List.of(), match("[1,2]", "[1,2]", NO_RULES));
        assertEquals(List.of("$: expected 2 element(s) but was 3"), match("[1,2]", "[1,2,3]", NO_RULES));
        assertEquals(List.of("$[1]: expected 2 but was 9"), match("[1,2]", "[1,9]", NO_RULES));
    }

    @Test
    void typeRuleAcceptsAnyValueOfTheSameTypeAndAppliesToTheWholeSubtree() {
        Map<String, List<Rule>> rules = Map.of("$.user", List.of(rule("type")));
        assertEquals(List.of(), match("{\"user\":{\"id\":1,\"name\":\"a\"}}", "{\"user\":{\"id\":77,\"name\":\"zed\",\"x\":1}}", rules));
        List<String> mismatches = match("{\"user\":{\"id\":1,\"name\":\"a\"}}", "{\"user\":{\"id\":\"77\"}}", rules);
        assertTrue(mismatches.contains("$.user.id: expected a number but was string"), mismatches.toString());
        assertTrue(mismatches.contains("$.user.name: missing from the response"), mismatches.toString());
    }

    @Test
    void typeRuleWithMinChecksArraySizeAndEveryElementAgainstTheFirstAsTemplate() {
        Map<String, List<Rule>> rules = Map.of("$", List.of(new Rule("type", null, 2, null, null)));
        assertEquals(List.of(), match("[{\"id\":1}]", "[{\"id\":5},{\"id\":6},{\"id\":7}]", rules));
        assertEquals(List.of("$: expected at least 2 element(s) but was 1"), match("[{\"id\":1}]", "[{\"id\":5}]", rules));
        List<String> mismatches = match("[{\"id\":1}]", "[{\"id\":5},{\"id\":\"x\"}]", rules);
        assertEquals(List.of("$[1].id: expected a number but was string"), mismatches);
    }

    @Test
    void wildcardIndexRulesApplyToEveryElement() {
        Map<String, List<Rule>> rules = Map.of(
            "$", List.of(new Rule("type", null, 1, null, null)),
            "$[*].id", List.of(rule("integer")));
        assertEquals(List.of(), match("[{\"id\":1}]", "[{\"id\":4},{\"id\":8}]", rules));
        assertEquals(List.of("$[0].id: expected an integer but was 4.5"), match("[{\"id\":1}]", "[{\"id\":4.5}]", rules));
    }

    @Test
    void regexAndLeafRulesDecideTheirNodeCompletely() {
        Map<String, List<Rule>> rules = Map.of(
            "$.email", List.of(new Rule("regex", ".+@.+\\..+", null, null, null)),
            "$.ok", List.of(rule("boolean")),
            "$.score", List.of(rule("decimal")),
            "$.note", List.of(new Rule("include", null, null, null, "sorry")),
            "$.gone", List.of(rule("null")));
        String expected = "{\"email\":\"a@b.co\",\"ok\":true,\"score\":1.5,\"note\":\"sorry about that\",\"gone\":null}";
        assertEquals(List.of(), match(expected, "{\"email\":\"zed@x.org\",\"ok\":false,\"score\":9.25,\"note\":\"so sorry!\",\"gone\":null}", rules));
        List<String> mismatches = match(expected, "{\"email\":\"nope\",\"ok\":\"yes\",\"score\":3,\"note\":\"fine\",\"gone\":1}", rules);
        assertEquals(5, mismatches.size(), mismatches.toString());
    }

    @Test
    void anUnsupportedRuleFailsInsteadOfPassingSilently() {
        List<String> mismatches = match("{\"d\":\"2020-01-01\"}", "{\"d\":\"2020-01-01\"}",
            Map.of("$.d", List.of(rule("date"))));
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("unsupported matching rule 'date'"), mismatches.get(0));
    }

    @Test
    void nullsAndScalarBodiesAreComparedToo() {
        assertEquals(List.of(), match("{\"a\":null}", "{\"a\":null}", NO_RULES));
        assertEquals(1, match("{\"a\":null}", "{\"a\":1}", NO_RULES).size());
        assertEquals(List.of(), match("\"text\"", "\"text\"", NO_RULES));
        assertEquals(1, match("\"text\"", "\"other\"", NO_RULES).size());
    }
}
