package com.automation.core.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicensePolicyTest {

    @Test
    void defaultsClassifyCommonLicenses() {
        LicensePolicy p = LicensePolicy.defaults();
        assertEquals(LicenseStatus.ALLOWED, p.classify(List.of("Apache-2.0")));
        assertEquals(LicenseStatus.ALLOWED, p.classify(List.of("apache-2.0")), "ids compare case-insensitively");
        assertEquals(LicenseStatus.DENIED, p.classify(List.of("GPL-3.0")));
        assertEquals(LicenseStatus.REVIEW, p.classify(List.of("LGPL-2.1")));
        assertEquals(LicenseStatus.REVIEW, p.classify(List.of("Some Custom Terms")));
        assertEquals(LicenseStatus.MISSING, p.classify(List.of()));
    }

    @Test
    void severalLicensesAreAlternatives() {
        LicensePolicy p = LicensePolicy.defaults();
        // Dual-licensed GPL OR Apache: the consumer may pick Apache.
        assertEquals(LicenseStatus.ALLOWED, p.classify(List.of("GPL-2.0", "Apache-2.0")));
        // Only denied ones -> denied.
        assertEquals(LicenseStatus.DENIED, p.classify(List.of("GPL-2.0", "AGPL-3.0")));
        // Denied + unknown -> a human has to look, not an automatic fail.
        assertEquals(LicenseStatus.REVIEW, p.classify(List.of("GPL-2.0", "Custom")));
    }

    @Test
    void defaultActionsFailOnlyDenied() {
        LicensePolicy p = LicensePolicy.defaults();
        assertEquals(LicensePolicy.Action.FAIL, p.actionFor(LicenseStatus.DENIED));
        assertEquals(LicensePolicy.Action.WARN, p.actionFor(LicenseStatus.REVIEW));
        assertEquals(LicensePolicy.Action.WARN, p.actionFor(LicenseStatus.MISSING));
        assertEquals(LicensePolicy.Action.IGNORE, p.actionFor(LicenseStatus.ALLOWED));
    }

    @Test
    void fileValuesAndSystemPropertyOverridesApply() {
        Properties props = new Properties();
        props.setProperty("license.allowed", "MIT");
        props.setProperty("license.review.action", "fail");
        props.setProperty("license.missing.action", "bogus");           // invalid -> fallback WARN
        LicensePolicy p = LicensePolicy.fromProperties(props,
            k -> "license.denied".equals(k) ? "MIT" : null);            // -D wins over file and defaults

        assertEquals(LicensePolicy.Action.FAIL, p.actionFor(LicenseStatus.REVIEW));
        assertEquals(LicensePolicy.Action.WARN, p.actionFor(LicenseStatus.MISSING));
        assertTrue(p.deniedIds().contains("mit"));
        assertFalse(p.allowedIds().contains("apache-2.0"), "an explicit allow list replaces the default list");
    }

    @Test
    void ignoreAndOverridePatterns() {
        Properties props = new Properties();
        props.setProperty("license.ignore", "com.mycompany:*, org.exact:thing");
        props.setProperty("license.overrides", "org.legacy:old-lib=MIT, org.multi:*=Apache-2.0|MIT, broken, =x, a:b=");
        LicensePolicy p = LicensePolicy.fromProperties(props, k -> null);

        assertTrue(p.isIgnored("com.mycompany", "anything"));
        assertTrue(p.isIgnored("org.exact", "thing"));
        assertFalse(p.isIgnored("org.exact", "other"));
        assertEquals(List.of("MIT"), p.overrideFor("org.legacy", "old-lib"));
        assertEquals(List.of("Apache-2.0", "MIT"), p.overrideFor("org.multi", "whatever"));
        assertTrue(p.overrideFor("org.none", "x").isEmpty());
    }

    @Test
    void missingFileMeansDefaults(@TempDir Path dir) throws Exception {
        LicensePolicy p = LicensePolicy.load(dir.resolve("does-not-exist.properties"));
        assertEquals(LicenseStatus.ALLOWED, p.classify(List.of("MIT")));
    }

    @Test
    void loadsFileFromDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policy.properties");
        Files.writeString(file, "license.allowed=Zlib\nlicense.denied=MIT\n");
        LicensePolicy p = LicensePolicy.load(file);
        assertEquals(LicenseStatus.ALLOWED, p.classify(List.of("Zlib")));
        assertEquals(LicenseStatus.DENIED, p.classify(List.of("MIT")));
    }
}
