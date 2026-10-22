package com.automation.core.license;

import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the dependency license scan.
 *
 * <p>Usage: {@code mvn -Plicense-check verify} or {@code mvn exec:java@license-scan -Plicense-check}
 * (see the {@code license-check} profile in pom.xml). Settings (all optional):
 * <ul>
 *   <li>{@code -Dlicense.policy=license-policy.properties} - policy file (default: that name, repo root)</li>
 *   <li>{@code -Dlicense.out=target/license-report} - report directory</li>
 *   <li>{@code -Dlicense.classpath.file=...} - scan this classpath instead of the running JVM's</li>
 * </ul>
 * The reports are always written. Exit status is non-zero only when the policy says a finding must fail
 * the build ({@code license.denied.action=FAIL}, the default for denied licenses; also
 * {@code license.review.action} / {@code license.missing.action}).
 */
public final class LicenseScanCli {

    private static final Logger logger = LoggerFactory.getLogger(LicenseScanCli.class);

    private LicenseScanCli() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run();
        } catch (Exception e) {
            logger.error("[LicenseScan] Failed: {}", e.getMessage(), e);
            status = 2;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Runs the scan and writes the reports; returns the process exit status (0 = pass). */
    static int run() throws Exception {
        Path policyFile = Path.of(System.getProperty("license.policy", "license-policy.properties"));
        Path outDir = Path.of(System.getProperty("license.out", "target/license-report"));

        List<Path> jars = ClasspathResolver.resolve();
        if (jars.isEmpty()) {
            System.err.println("[LicenseScan] No dependency jars found on the classpath - nothing to scan.");
            return 2;
        }
        LicenseScanResult result = scanAndWrite(policyFile, outDir, jars);
        System.out.print(LicenseReportWriter.text(result));
        System.out.println("[LicenseScan] Report: " + outDir.resolve(LicenseReportWriter.HTML));

        List<DependencyLicense> failing = result.violations();
        if (!failing.isEmpty()) {
            System.err.println("[LicenseScan] FAILED - " + failing.size()
                + " dependency license(s) violate the policy (see " + policyFile + ").");
            return 1;
        }
        return 0;
    }

    /** Loads the policy, scans {@code jars}, writes the four report files into {@code outDir}. */
    public static LicenseScanResult scanAndWrite(Path policyFile, Path outDir, List<Path> jars) throws Exception {
        LicensePolicy policy = LicensePolicy.load(policyFile);
        LicenseScanResult result = new LicenseScanner(policy).scan(jars);
        LicenseReportWriter.write(result, outDir);
        return result;
    }
}
