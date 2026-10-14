package com.automation.core.contract;

import com.automation.core.contract.Contract.Rule;
import com.automation.core.utils.SensitiveData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compares a provider's actual JSON body with the body a contract expects. Pure logic.
 *
 * <p>Without matching rules the comparison is the Pact default: every field the contract lists
 * must be present with an equal value, but the provider may return EXTRA fields (a consumer only
 * depends on what it uses); arrays must have the same length and match element by element.
 * A {@code type} rule relaxes a node - and everything under it - to "same JSON type, same
 * shape", with an array checked against its first element as a template; {@code regex},
 * {@code integer}, {@code decimal}, {@code number}, {@code boolean}, {@code null},
 * {@code include} and {@code equality} rules apply to that single node.
 *
 * <p>A rule kind this class does not know is reported as a mismatch - an unsupported rule must
 * never turn into a silent pass.
 */
public final class BodyMatcher {

    private BodyMatcher() {
    }

    /** Human-readable mismatches, empty when the actual body satisfies the contract. */
    public static List<String> match(JsonNode expected, JsonNode actual, Map<String, List<Rule>> rules) {
        List<String> mismatches = new ArrayList<>();
        walk("$", expected, actual, rules, false, mismatches);
        return mismatches;
    }

    private static void walk(String path, JsonNode expected, JsonNode actual, Map<String, List<Rule>> rules,
                             boolean typeMode, List<String> out) {
        if (actual == null || actual.isMissingNode()) {
            out.add(path + ": missing from the response");
            return;
        }
        List<Rule> ruleList = lookup(path, rules);
        boolean types = typeMode;
        if (ruleList != null) {
            for (Rule rule : ruleList) {
                if (!applyRule(path, rule, expected, actual, out)) {
                    return;
                }
                if (rule.match().equals("type")) {
                    types = true;
                }
            }
            if (ruleList.stream().anyMatch(r -> r.match().equals("regex") || r.match().equals("equality")
                || r.match().equals("include") || r.match().equals("integer") || r.match().equals("decimal")
                || r.match().equals("number") || r.match().equals("boolean") || r.match().equals("null"))) {
                return; // a leaf rule decides this node completely
            }
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                out.add(path + ": expected an object but was " + kind(actual));
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = expected.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                walk(path + "." + field.getKey(), field.getValue(), actual.get(field.getKey()), rules, types, out);
            }
        } else if (expected.isArray()) {
            if (!actual.isArray()) {
                out.add(path + ": expected an array but was " + kind(actual));
                return;
            }
            if (types) {
                if (expected.size() > 0) {
                    for (int i = 0; i < actual.size(); i++) {
                        walk(path + "[" + i + "]", expected.get(0), actual.get(i), rules, true, out);
                    }
                }
            } else if (expected.size() != actual.size()) {
                out.add(path + ": expected " + expected.size() + " element(s) but was " + actual.size());
            } else {
                for (int i = 0; i < expected.size(); i++) {
                    walk(path + "[" + i + "]", expected.get(i), actual.get(i), rules, false, out);
                }
            }
        } else if (types) {
            if (!sameType(expected, actual)) {
                out.add(path + ": expected a " + kind(expected) + " but was " + kind(actual));
            }
        } else if (!scalarEquals(expected, actual)) {
            out.add(path + ": expected " + show(path, expected) + " but was " + show(path, actual));
        }
    }

    /** Applies one rule to the node itself; false = stop descending (a mismatch was recorded). */
    private static boolean applyRule(String path, Rule rule, JsonNode expected, JsonNode actual, List<String> out) {
        switch (rule.match()) {
            case "type":
                if (!sameType(expected, actual)) {
                    out.add(path + ": expected a " + kind(expected) + " but was " + kind(actual));
                    return false;
                }
                return checkSize(path, rule, actual, out);
            case "regex":
                return matchesRegex(path, rule, actual, out);
            case "integer":
                return expect(actual.isIntegralNumber(), path, "an integer", actual, out);
            case "decimal":
                return expect(actual.isNumber() && !actual.isIntegralNumber(), path, "a decimal number", actual, out);
            case "number":
                return expect(actual.isNumber(), path, "a number", actual, out);
            case "boolean":
                return expect(actual.isBoolean(), path, "a boolean", actual, out);
            case "null":
                return expect(actual.isNull(), path, "null", actual, out);
            case "include":
                return expect(actual.isValueNode() && actual.asText().contains(rule.value() == null ? "" : rule.value()),
                    path, "text containing '" + rule.value() + "'", actual, out);
            case "equality":
                return expect(scalarEquals(expected, actual) || expected.equals(actual), path, expected.toString(), actual, out);
            case "min":
            case "max":
                return checkSize(path, rule, actual, out);
            default:
                out.add(path + ": unsupported matching rule '" + rule.match() + "' (supported: type, regex, integer, "
                    + "decimal, number, boolean, null, include, equality, min, max)");
                return false;
        }
    }

    /** Work allowed for ONE regex match, counted in characters read; far above any sane pattern, far below a hang. */
    static final int REGEX_BUDGET = 2_000_000;
    private static final int REGEX_MAX_INPUT = 100_000;

    /**
     * Full-match of the node's text against the rule's regex. The text is the PROVIDER's answer, i.e.
     * input an attacker (or a buggy service) controls, and the regex comes from a contract file, so a
     * pattern like {@code (.*a){25}} could otherwise keep the run busy for hours: the match is given a
     * fixed budget of characters read and fails with a clear message when it runs out. A missing or
     * invalid regex is a mismatch, not a crash.
     */
    private static boolean matchesRegex(String path, Rule rule, JsonNode actual, List<String> out) {
        if (rule.regex() == null || rule.regex().isEmpty()) {
            out.add(path + ": the contract's regex rule has no regex");
            return false;
        }
        if (!actual.isValueNode()) {
            out.add(path + ": " + show(path, actual) + " does not match /" + rule.regex() + "/");
            return false;
        }
        String text = actual.asText();
        if (text.length() > REGEX_MAX_INPUT) {
            out.add(path + ": value is " + text.length() + " characters long - too long to match against /" + rule.regex() + "/");
            return false;
        }
        try {
            if (Pattern.compile(rule.regex()).matcher(new BudgetedText(text, REGEX_BUDGET)).matches()) {
                return true;
            }
            out.add(path + ": " + show(path, actual) + " does not match /" + rule.regex() + "/");
        } catch (PatternSyntaxException e) {
            out.add(path + ": invalid regex /" + rule.regex() + "/ in the contract (" + e.getDescription() + ")");
        } catch (BudgetExceeded e) {
            out.add(path + ": matching /" + rule.regex() + "/ took too long (catastrophic backtracking?) - "
                + "use a simpler pattern");
        }
        return false;
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super("regex budget exceeded", null, false, false);
        }
    }

    /** A CharSequence that gives up after a fixed number of reads, which interrupts a runaway regex. */
    private static final class BudgetedText implements CharSequence {
        private final CharSequence text;
        private int remaining;

        BudgetedText(CharSequence text, int budget) {
            this.text = text;
            this.remaining = budget;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) {
                throw new BudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedText(text.subSequence(start, end), Math.max(remaining, 0));
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static boolean checkSize(String path, Rule rule, JsonNode actual, List<String> out) {
        if (!actual.isArray() && !actual.isTextual()) {
            return true;
        }
        int size = actual.isArray() ? actual.size() : actual.asText().length();
        if (rule.min() != null && size < rule.min()) {
            out.add(path + ": expected at least " + rule.min() + " element(s) but was " + size);
            return false;
        }
        if (rule.max() != null && size > rule.max()) {
            out.add(path + ": expected at most " + rule.max() + " element(s) but was " + size);
            return false;
        }
        return true;
    }

    private static boolean expect(boolean ok, String path, String wanted, JsonNode actual, List<String> out) {
        if (!ok) {
            out.add(path + ": expected " + wanted + " but was " + show(path, actual));
        }
        return ok;
    }

    /** Exact path, else the same path with every concrete index turned into {@code [*]}. */
    private static List<Rule> lookup(String path, Map<String, List<Rule>> rules) {
        if (rules.isEmpty()) {
            return null;
        }
        List<Rule> exact = rules.get(path);
        return exact != null ? exact : rules.get(path.replaceAll("\\[\\d+]", "[*]"));
    }

    private static final int MAX_SHOWN_CHARS = 100;

    /** A value for a message: masked when the field's name looks sensitive (token, password, ...), cut when long. */
    static String show(String path, JsonNode node) {
        String field = path.replaceAll("\\[[^\\]]*]$", "");
        field = field.substring(field.lastIndexOf('.') + 1);
        if (SensitiveData.isSensitiveName(field)) {
            return SensitiveData.MASK;
        }
        String text = node.toString();
        return text.length() > MAX_SHOWN_CHARS ? text.substring(0, MAX_SHOWN_CHARS) + "...(" + text.length() + " chars)" : text;
    }

    private static boolean sameType(JsonNode expected, JsonNode actual) {
        return expected.getNodeType() == actual.getNodeType()
            || (expected.isNumber() && actual.isNumber());
    }

    private static boolean scalarEquals(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return new BigDecimal(expected.asText()).compareTo(new BigDecimal(actual.asText())) == 0;
        }
        return expected.equals(actual);
    }

    private static String kind(JsonNode node) {
        JsonNodeType type = node.getNodeType();
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }
}
