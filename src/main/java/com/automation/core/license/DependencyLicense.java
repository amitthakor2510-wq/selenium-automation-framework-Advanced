package com.automation.core.license;

import java.util.List;

/**
 * License findings for a single dependency jar.
 *
 * @param groupId       Maven groupId ("(unknown)" when it could not be determined)
 * @param artifactId    Maven artifactId (or the jar file name when no POM information exists)
 * @param version       artifact version ("" when unknown)
 * @param declared      license names exactly as the POM declares them (may be empty)
 * @param licenseIds    the normalised identifiers the policy was evaluated against
 * @param status        the verdict
 * @param source        where the license data came from (pom, parent pom, embedded pom, override, none)
 * @param note          extra explanation, e.g. why nothing was found ("" when none)
 */
public record DependencyLicense(String groupId, String artifactId, String version,
                                List<String> declared, List<String> licenseIds,
                                LicenseStatus status, String source, String note) {

    public DependencyLicense {
        declared = List.copyOf(declared);
        licenseIds = List.copyOf(licenseIds);
    }

    /** {@code group:artifact:version}. */
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
