package com.automation.core.license;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one scan produced.
 *
 * @param dependencies findings, worst status first
 * @param ignored      jars skipped by {@code license.ignore}
 * @param policy       the policy the findings were judged by
 */
public record LicenseScanResult(List<DependencyLicense> dependencies, int ignored, LicensePolicy policy) {

    public LicenseScanResult {
        dependencies = List.copyOf(dependencies);
    }

    public int count(LicenseStatus status) {
        return (int) dependencies.stream().filter(d -> d.status() == status).count();
    }

    public Map<LicenseStatus, Integer> counts() {
        Map<LicenseStatus, Integer> m = new EnumMap<>(LicenseStatus.class);
        for (LicenseStatus s : LicenseStatus.values()) {
            m.put(s, count(s));
        }
        return m;
    }

    /** The findings the policy says must fail the build ({@code *.action=FAIL}). */
    public List<DependencyLicense> violations() {
        return dependencies.stream()
            .filter(d -> policy.actionFor(d.status()) == LicensePolicy.Action.FAIL).toList();
    }

    /** The findings the policy says to flag but not fail on ({@code *.action=WARN}). */
    public List<DependencyLicense> warnings() {
        return dependencies.stream()
            .filter(d -> policy.actionFor(d.status()) == LicensePolicy.Action.WARN).toList();
    }
}
