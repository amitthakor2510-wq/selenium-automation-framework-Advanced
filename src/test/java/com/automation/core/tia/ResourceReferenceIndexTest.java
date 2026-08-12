package com.automation.core.tia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceReferenceIndexTest {

    @Test
    void findsClassThatLiterallyReferencesResourcePath(@TempDir Path repoRoot) {
        TiaTestFixtures.write(repoRoot, "src/test/java/com/automation/sites/saucedemo/tests/LoginDataDrivenTest.java",
            "package com.automation.sites.saucedemo.tests;\n"
                + "public class LoginDataDrivenTest {\n"
                + "  private static final String DATA = \"src/test/resources/testdata/login.csv\";\n"
                + "}\n");

        ResourceReferenceIndex index = ResourceReferenceIndex.build(repoRoot);
        Set<String> refs = index.referencingClasses("src/test/resources/testdata/login.csv");

        assertEquals(Set.of("com.automation.sites.saucedemo.tests.LoginDataDrivenTest"), refs);
    }

    @Test
    void resourceWithNoLiteralReferenceIsEmpty(@TempDir Path repoRoot) {
        TiaTestFixtures.write(repoRoot, "src/test/java/com/automation/sites/demoqa/tests/ButtonsTest.java",
            "package com.automation.sites.demoqa.tests;\npublic class ButtonsTest {}\n");

        ResourceReferenceIndex index = ResourceReferenceIndex.build(repoRoot);
        assertTrue(index.referencingClasses("src/test/resources/testdata/keyword/demoqa_textbox_keywords.csv").isEmpty());
    }

    @Test
    void matchesByBasenameRegardlessOfHowThePathWasWritten(@TempDir Path repoRoot) {
        TiaTestFixtures.write(repoRoot, "src/test/java/com/automation/sites/demoqa/tests/KeywordDrivenTextBoxTest.java",
            "package com.automation.sites.demoqa.tests;\n"
                + "public class KeywordDrivenTextBoxTest {\n"
                + "  private static final String SCRIPT = \"src/test/resources/testdata/keyword/demoqa_textbox_keywords.csv\";\n"
                + "}\n");

        ResourceReferenceIndex index = ResourceReferenceIndex.build(repoRoot);
        // Looked up by a path with the same basename but different (still repo-relative) prefix.
        Set<String> refs = index.referencingClasses("testdata/keyword/demoqa_textbox_keywords.csv");
        assertEquals(Set.of("com.automation.sites.demoqa.tests.KeywordDrivenTextBoxTest"), refs);
    }
}
