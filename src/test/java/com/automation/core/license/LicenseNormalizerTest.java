package com.automation.core.license;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseNormalizerTest {

    private static List<String> n(String name) {
        return LicenseNormalizer.normalize(name, "");
    }

    @Test
    void apacheVariants() {
        assertEquals(List.of("Apache-2.0"), n("The Apache Software License, Version 2.0"));
        assertEquals(List.of("Apache-2.0"), n("Apache License, Version 2.0"));
        assertEquals(List.of("Apache-2.0"), n("Apache-2.0"));
        assertEquals(List.of("Apache-2.0"), n("Apache 2"));
    }

    @Test
    void mitAndBsdVariants() {
        assertEquals(List.of("MIT"), n("The MIT License"));
        assertEquals(List.of("MIT"), n("MIT license"));
        assertEquals(List.of("BSD-3-Clause"), n("BSD-3-Clause"));
        assertEquals(List.of("BSD-3-Clause"), n("The 3-Clause BSD License"));
        assertEquals(List.of("BSD-3-Clause"), n("New BSD License"));
        assertEquals(List.of("BSD-2-Clause"), n("BSD 2-Clause License"));
        assertEquals(List.of("BSD-2-Clause"), n("Simplified BSD License"));
        assertEquals(List.of("BSD-4-Clause"), n("BSD 4-Clause"));
    }

    @Test
    void versionGluedToWordIsStillRecognised() {
        // "v2.0" / "v 2.0" / "2.0" must all give the same answer.
        assertEquals(List.of("EPL-2.0"), n("Eclipse Public License v2.0"));
        assertEquals(List.of("EPL-2.0"), n("Eclipse Public License - v 2.0"));
        assertEquals(List.of("EPL-2.0"), n("EPL 2.0"));
        assertEquals(List.of("EPL-1.0"), n("Eclipse Public License - v 1.0"));
        assertEquals(List.of("BSD-3-Clause"), n("Eclipse Distribution License - v 1.0"));
    }

    @Test
    void gnuFamilyIsToldApart() {
        assertEquals(List.of("LGPL-2.1"), n("GNU Lesser General Public License v2.1"));
        assertEquals(List.of("LGPL-3.0"), n("GNU Lesser General Public License, Version 3"));
        assertEquals(List.of("GPL-2.0"), n("GNU General Public License, version 2"));
        assertEquals(List.of("GPL-3.0"), n("GNU General Public License v3.0"));
        assertEquals(List.of("AGPL-3.0"), n("GNU Affero General Public License v3"));
        assertEquals(List.of("GPL-2.0-with-classpath-exception"),
            n("GNU General Public License, version 2, with the Classpath Exception"));
    }

    @Test
    void dualLicenseYieldsBothIds() {
        List<String> ids = n("CDDL + GPLv2 with classpath exception");
        assertTrue(ids.contains("CDDL-1.1"), ids.toString());
        assertTrue(ids.contains("GPL-2.0-with-classpath-exception"), ids.toString());
        assertEquals(List.of("CDDL-1.0"), n("COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0"));
    }

    @Test
    void otherKnownLicenses() {
        assertEquals(List.of("MPL-2.0"), n("Mozilla Public License 2.0"));
        assertEquals(List.of("MPL-1.1"), n("Mozilla Public License 1.1"));
        assertEquals(List.of("CC0-1.0"), n("CC0 1.0 Universal"));
        assertEquals(List.of("Public-Domain"), n("Public Domain"));
        assertEquals(List.of("SSPL-1.0"), n("Server Side Public License"));
        assertEquals(List.of("JSON"), n("The JSON License"));
    }

    @Test
    void wordBoundariesPreventFalsePositives() {
        // "limit"/"permit"/"submit" contain "mit"; "gplx" contains "gpl"; none may match.
        assertTrue(n("Unlimited permit to submit").isEmpty());
        assertTrue(n("Totally custom corporate terms").isEmpty());
    }

    @Test
    void urlIsUsedOnlyWhenTheNameIsNotRecognised() {
        assertEquals(List.of("Apache-2.0"),
            LicenseNormalizer.normalize("See website", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
        assertEquals(List.of("MIT"),
            LicenseNormalizer.normalize("", "https://opensource.org/licenses/MIT"));
        assertEquals(List.of("MIT"),
            LicenseNormalizer.normalize("MIT", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
        assertTrue(LicenseNormalizer.normalize(null, null).isEmpty());
        assertTrue(LicenseNormalizer.normalize("  ", "  ").isEmpty());
    }
}
