package com.automation.core.license;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scans dependency jars and judges each one's license(s) against a {@link LicensePolicy}. */
public final class LicenseScanner {

    private final LicensePolicy policy;

    public LicenseScanner(LicensePolicy policy) {
        this.policy = policy;
    }

    public LicenseScanResult scan(List<Path> jars) {
        // One row per artifact version even when the same artifact is on the classpath as several jars
        // (e.g. a -tests or -sources classifier jar).
        Map<String, DependencyLicense> byCoordinates = new LinkedHashMap<>();
        int ignored = 0;
        for (Path jar : jars) {
            PomLicenseReader.Result found = PomLicenseReader.readForJar(jar);
            if (policy.isIgnored(found.groupId(), found.artifactId())) {
                ignored++;
                continue;
            }
            DependencyLicense row = judge(found);
            byCoordinates.putIfAbsent(row.coordinates(), row);
        }
        List<DependencyLicense> rows = new ArrayList<>(byCoordinates.values());
        rows.sort(Comparator.comparing(DependencyLicense::status)
            .thenComparing(DependencyLicense::groupId).thenComparing(DependencyLicense::artifactId));
        return new LicenseScanResult(rows, ignored, policy);
    }

    private DependencyLicense judge(PomLicenseReader.Result found) {
        List<String> declared = found.licenses().stream()
            .map(l -> l.name().isBlank() ? l.url() : l.name()).toList();
        List<String> ids;
        String source = found.source();
        String note = found.note();

        List<String> override = policy.overrideFor(found.groupId(), found.artifactId());
        if (!override.isEmpty()) {
            ids = override;
            source = "policy override";
            note = "";
        } else {
            ids = new ArrayList<>();
            for (PomLicenseReader.RawLicense raw : found.licenses()) {
                List<String> normalised = LicenseNormalizer.normalize(raw.name(), raw.url());
                if (normalised.isEmpty()) {
                    // Unrecognised text is kept verbatim so it shows up for review instead of vanishing.
                    ids.add(raw.name().isBlank() ? raw.url() : raw.name());
                } else {
                    ids.addAll(normalised);
                }
            }
        }
        List<String> distinct = ids.stream().distinct().toList();
        return new DependencyLicense(found.groupId(), found.artifactId(), found.version(), declared, distinct,
            policy.classify(distinct), source, note);
    }
}
