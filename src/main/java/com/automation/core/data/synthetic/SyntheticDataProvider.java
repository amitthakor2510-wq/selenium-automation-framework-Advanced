package com.automation.core.data.synthetic;

import com.automation.core.data.DataRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link SyntheticDataGenerator} output into {@link DataRow} lists — the same type every
 * file-backed {@code @DataProvider} in this project already produces (see
 * {@code core/data/DataProvider}/{@code DataProviderFactory}), so a test written against
 * synthetic data looks and behaves exactly like one written against a CSV/Excel/JSON file. Use
 * via {@code DataProviderFactory.syntheticRegistrations(count)} /
 * {@code DataProviderFactory.syntheticRegistrationEdgeCases()} rather than calling this class
 * directly, unless you need a data shape those convenience methods don't cover.
 */
public final class SyntheticDataProvider {

    private SyntheticDataProvider() {
    }

    /**
     * {@code count} realistic-looking registration rows (firstName/lastName/username/email/
     * password), freshly generated (or deterministically regenerated if {@code
     * synthetic.data.seed} is set — see {@link SyntheticDataGenerator}). Column names match what
     * {@code RegistrationSyntheticDataTest} reads via {@code DataRow.getRequired(...)}.
     */
    public static List<DataRow> registrations(int count) {
        SyntheticDataGenerator gen = new SyntheticDataGenerator();
        List<DataRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("firstname", gen.firstName());
            row.put("lastname", gen.lastName());
            row.put("username", gen.uniqueUsername());
            row.put("email", gen.email());
            row.put("password", gen.strongPassword());
            row.put("notes", "synthetic (Faker)");
            rows.add(new DataRow(row, i));
        }
        return rows;
    }

    /**
     * One registration row per value in {@link SyntheticDataGenerator#edgeCaseStrings()},
     * applied to the <b>username</b> field only (the field this project's registration flow can
     * actually distinguish a validation failure on via the alert/#name error text — see
     * {@code RegistrationPage.isRegistrationSuccessful}) — first/last name/email/password stay
     * fixed at realistic, otherwise-valid values so a failure is attributable to the username
     * value under test, not a coincidentally-also-invalid other field.
     */
    public static List<DataRow> registrationUsernameEdgeCases() {
        SyntheticDataGenerator gen = new SyntheticDataGenerator();
        String fixedFirstName = gen.firstName();
        String fixedLastName = gen.lastName();
        String fixedEmail = gen.email();
        String fixedPassword = gen.strongPassword();

        List<String> edgeValues = SyntheticDataGenerator.edgeCaseStrings();
        List<DataRow> rows = new ArrayList<>();
        for (int i = 0; i < edgeValues.size(); i++) {
            String edgeValue = edgeValues.get(i);
            // A real username still needs to be unique per run, or a stale "already exists"
            // response from a previous run would be indistinguishable from a genuine validation
            // rejection — suffix (not prefix, so a maxlength-boundary edge case isn't disturbed
            // at the end where truncation would happen) only for the non-empty/non-whitespace
            // cases, where a suffix doesn't change what's actually being tested.
            String username = edgeValue.isBlank() ? edgeValue : edgeValue + "_" + i;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("firstname", fixedFirstName);
            row.put("lastname", fixedLastName);
            row.put("username", username);
            row.put("email", fixedEmail);
            row.put("password", fixedPassword);
            row.put("notes", "edge case: " + describe(edgeValue));
            rows.add(new DataRow(row, i));
        }
        return rows;
    }

    private static String describe(String value) {
        if (value.isEmpty()) {
            return "empty string";
        }
        if (value.isBlank()) {
            return "whitespace-only";
        }
        if (value.length() > 100) {
            return "very long (" + value.length() + " chars)";
        }
        return "'" + value + "'";
    }
}
