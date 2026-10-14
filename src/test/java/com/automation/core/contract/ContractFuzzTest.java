package com.automation.core.contract;

import com.automation.core.contract.Contract.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeded random documents through the matcher and the contract parser: properties that must always hold. */
class ContractFuzzTest {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int ROUNDS = 1500;

    /** Random JSON; arrays are homogeneous (every element is a copy of one shape) when {@code homogeneous}. */
    private static JsonNode random(Random r, int depth, boolean homogeneous) {
        int kind = r.nextInt(depth <= 0 ? 5 : 7);
        switch (kind) {
            case 0:
                return F.numberNode(r.nextInt(1000));
            case 1:
                return F.numberNode(r.nextInt(1000) / 7.0);
            case 2:
                return F.textNode("s" + r.nextInt(50));
            case 3:
                return F.booleanNode(r.nextBoolean());
            case 4:
                return F.nullNode();
            case 5:
                return randomObject(r, depth, homogeneous);
            default:
                return randomArray(r, depth, homogeneous);
        }
    }

    private static JsonNode randomObject(Random r, int depth, boolean homogeneous) {
        ObjectNode object = F.objectNode();
        for (int i = r.nextInt(5); i > 0; i--) {
            object.set("k" + r.nextInt(8), random(r, depth - 1, homogeneous));
        }
        return object;
    }

    private static JsonNode randomArray(Random r, int depth, boolean homogeneous) {
        ArrayNode array = F.arrayNode();
        int size = r.nextInt(5);
        JsonNode shape = random(r, depth - 1, homogeneous);
        for (int i = 0; i < size; i++) {
            array.add(homogeneous ? shape.deepCopy() : random(r, depth - 1, false));
        }
        return array;
    }

    private static void addExtraFields(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).put("zz_extra_field", 1);
            node.forEach(ContractFuzzTest::addExtraFields);
        } else if (node.isArray()) {
            node.forEach(ContractFuzzTest::addExtraFields);
        }
    }

    /** Replaces one random leaf of {@code root} (in place) and returns whether a leaf existed. */
    private static boolean mutateOneLeaf(JsonNode root, Random r) {
        List<Object[]> leaves = new ArrayList<>(); // {parent, key or index}
        collectLeaves(root, leaves);
        if (leaves.isEmpty()) {
            return false;
        }
        Object[] leaf = leaves.get(r.nextInt(leaves.size()));
        JsonNode replacement = TextNode.valueOf("MUTATED_" + r.nextInt(1000));
        if (leaf[0] instanceof ObjectNode) {
            ((ObjectNode) leaf[0]).set((String) leaf[1], replacement);
        } else {
            ((ArrayNode) leaf[0]).set((Integer) leaf[1], replacement);
        }
        return true;
    }

    private static void collectLeaves(JsonNode node, List<Object[]> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isContainerNode()) {
                    collectLeaves(field.getValue(), out);
                } else {
                    out.add(new Object[] {node, field.getKey()});
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                if (node.get(i).isContainerNode()) {
                    collectLeaves(node.get(i), out);
                } else {
                    out.add(new Object[] {node, i});
                }
            }
        }
    }

    @Test
    void everyDocumentMatchesItselfAndExtraProviderFieldsNeverBreakAMatch() {
        Random r = new Random(42);
        for (int i = 0; i < ROUNDS; i++) {
            JsonNode expected = random(r, 3, false);
            assertEquals(List.of(), BodyMatcher.match(expected, expected.deepCopy(), Map.of()), "identity: " + expected);
            JsonNode withExtras = expected.deepCopy();
            addExtraFields(withExtras);
            assertEquals(List.of(), BodyMatcher.match(expected, withExtras, Map.of()), "extras: " + expected);
        }
    }

    @Test
    void aTypeRuleAtTheRootStillAcceptsTheSameDocument() {
        Random r = new Random(43);
        Map<String, List<Rule>> rootType = Map.of("$", List.of(new Rule("type", null, null, null, null)));
        for (int i = 0; i < ROUNDS; i++) {
            JsonNode expected = random(r, 3, true);
            assertEquals(List.of(), BodyMatcher.match(expected, expected.deepCopy(), rootType), "type identity: " + expected);
        }
    }

    @Test
    void changingAnyOneValueIsAlwaysCaughtWithoutRules() {
        Random r = new Random(44);
        int checked = 0;
        for (int i = 0; i < ROUNDS; i++) {
            JsonNode expected = random(r, 3, false);
            JsonNode actual = expected.deepCopy();
            if (!mutateOneLeaf(actual, r)) {
                continue;
            }
            checked++;
            assertFalse(BodyMatcher.match(expected, actual, Map.of()).isEmpty(), "missed a change in " + expected + " -> " + actual);
        }
        assertTrue(checked > 300, "the generator must exercise the property often enough: " + checked);
    }

    @Test
    void anyPairOfDocumentsWithAnyRulesNeverThrows() {
        Random r = new Random(45);
        String[] kinds = {"type", "regex", "integer", "decimal", "number", "boolean", "null", "include", "equality", "min", "max", "date", "", "bogus"};
        String[] regexes = {null, "", "(", "[", ".*", "s\\d+", "(.*a){25}", "\\", "(?<n>x)\\k<n>"};
        String[] paths = {"$", "$.k1", "$.k2.k3", "$[*]", "$[0]", "$.k1[*]", "$.k1[*].k2", "$[*].k1"};
        for (int i = 0; i < ROUNDS; i++) {
            Map<String, List<Rule>> rules = new java.util.LinkedHashMap<>();
            for (int n = r.nextInt(4); n > 0; n--) {
                rules.put(paths[r.nextInt(paths.length)], List.of(new Rule(kinds[r.nextInt(kinds.length)],
                    regexes[r.nextInt(regexes.length)], r.nextBoolean() ? r.nextInt(4) : null, r.nextBoolean() ? r.nextInt(4) : null, "s")));
            }
            JsonNode expected = random(r, 3, false);
            JsonNode actual = r.nextBoolean() ? expected.deepCopy() : random(r, 3, false);
            try {
                BodyMatcher.match(expected, actual, rules);
            } catch (RuntimeException | StackOverflowError e) {
                throw new AssertionError("matcher threw " + e + " for expected=" + expected + " actual=" + actual + " rules=" + rules, e);
            }
        }
    }

    @Test
    void parsingAndValidatingArbitraryJsonNeverThrows() throws Exception {
        Random r = new Random(46);
        ObjectMapper mapper = new ObjectMapper();
        String[] keys = {"interactions", "consumer", "provider", "request", "response", "description", "method", "path", "query",
            "headers", "body", "status", "matchingRules", "providerState", "providerStates", "matchers", "match", "min", "regex"};
        for (int i = 0; i < ROUNDS; i++) {
            JsonNode root = garbage(r, 4, keys);
            try {
                Contract contract = Contract.parse(root, "fuzz.json");
                contract.validate();
                for (Contract.Interaction interaction : contract.interactions()) {
                    Contract.requestProblems(interaction.request());
                }
            } catch (RuntimeException e) {
                throw new AssertionError("Contract threw " + e + " for " + mapper.writeValueAsString(root), e);
            }
        }
    }

    private static JsonNode garbage(Random r, int depth, String[] keys) {
        int kind = r.nextInt(depth <= 0 ? 4 : 6);
        switch (kind) {
            case 0:
                return F.numberNode(r.nextInt(700));
            case 1:
                return F.textNode(r.nextBoolean() ? "/p" + r.nextInt(9) : "x\r\ny " + r.nextInt(9));
            case 2:
                return F.booleanNode(r.nextBoolean());
            case 3:
                return F.nullNode();
            case 4:
                return garbageObject(r, depth, keys);
            default:
                return garbageArray(r, depth, keys);
        }
    }

    private static JsonNode garbageObject(Random r, int depth, String[] keys) {
        ObjectNode object = F.objectNode();
        for (int i = r.nextInt(6); i > 0; i--) {
            object.set(keys[r.nextInt(keys.length)], garbage(r, depth - 1, keys));
        }
        return object;
    }

    private static JsonNode garbageArray(Random r, int depth, String[] keys) {
        ArrayNode array = F.arrayNode();
        for (int i = r.nextInt(4); i > 0; i--) {
            array.add(garbage(r, depth - 1, keys));
        }
        return array;
    }
}
