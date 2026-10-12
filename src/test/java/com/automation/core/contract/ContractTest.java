package com.automation.core.contract;

import com.automation.core.contract.Contract.Interaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of Pact JSON, v2 and v3 layouts. */
class ContractTest {

    static final String V2 = "{\"consumer\":{\"name\":\"web\"},\"provider\":{\"name\":\"books\"},\"interactions\":[{"
        + "\"description\":\"list\",\"providerState\":\"books exist\","
        + "\"request\":{\"method\":\"get\",\"path\":\"/books\",\"query\":\"a=1&b=x%20y&a=2\",\"headers\":{\"Accept\":\"application/json\"}},"
        + "\"response\":{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"body\":[{\"id\":1}],"
        + "\"matchingRules\":{\"$.body\":{\"min\":1},\"$.body[*].id\":{\"match\":\"integer\"}}}}]}";

    static final String V3 = "{\"consumer\":{\"name\":\"web\"},\"provider\":{\"name\":\"books\"},\"interactions\":[{"
        + "\"description\":\"create\",\"providerStates\":[{\"name\":\"empty shelf\"},{\"name\":\"logged in\"}],"
        + "\"request\":{\"method\":\"POST\",\"path\":\"/books\",\"query\":{\"dry\":[\"true\"]},\"body\":{\"title\":\"x\"}},"
        + "\"response\":{\"status\":201,\"body\":{\"id\":7},"
        + "\"matchingRules\":{\"body\":{\"$.id\":{\"matchers\":[{\"match\":\"integer\"}],\"combine\":\"AND\"}}}}}]}";

    private static Contract parse(String json) throws IOException {
        return Contract.parse(new ObjectMapper().readTree(json), "test.json");
    }

    @Test
    void parsesAV2Contract() throws IOException {
        Contract contract = parse(V2);
        assertEquals("web", contract.consumer());
        assertEquals("books", contract.provider());
        Interaction interaction = contract.interactions().get(0);
        assertEquals(List.of("books exist"), interaction.providerStates());
        assertEquals("GET", interaction.request().method(), "method is upper-cased");
        assertEquals(List.of("1", "2"), interaction.request().query().get("a"), "repeated parameters are kept in order");
        assertEquals(List.of("x y"), interaction.request().query().get("b"), "percent-encoding is decoded once, at parse time");
        assertEquals("application/json", interaction.request().headers().get("Accept"));
        assertEquals(200, interaction.response().status());
        assertTrue(interaction.response().bodyRules().containsKey("$"), "$.body became $");
        assertEquals("type", interaction.response().bodyRules().get("$").get(0).match(), "{min} alone implies a type rule");
        assertEquals(Integer.valueOf(1), interaction.response().bodyRules().get("$").get(0).min());
        assertEquals("integer", interaction.response().bodyRules().get("$[*].id").get(0).match());
    }

    @Test
    void parsesAV3Contract() throws IOException {
        Interaction interaction = parse(V3).interactions().get(0);
        assertEquals(List.of("empty shelf", "logged in"), interaction.providerStates());
        assertEquals(List.of("true"), interaction.request().query().get("dry"));
        assertEquals("x", interaction.request().body().get("title").asText());
        assertEquals(201, interaction.response().status());
        assertEquals("integer", interaction.response().bodyRules().get("$.id").get(0).match());
    }

    @Test
    void anInteractionWithoutAResponseBodyHasNoBodyToCheck() throws IOException {
        Interaction interaction = parse("{\"interactions\":[{\"request\":{\"path\":\"/x\"},\"response\":{\"status\":404}}]}")
            .interactions().get(0);
        assertEquals(null, interaction.response().body());
        assertEquals("GET", interaction.request().method(), "method defaults to GET");
        assertEquals("(no description)", interaction.description());
    }
}
