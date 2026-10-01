package com.automation.core.tia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests against a real temp git repository and real {@code javac} output — no mocking
 * of git or the class file format. Mirrors the actual {@code src/main/java} / {@code src/test/java}
 * / {@code src/test/resources} shape this framework uses, at a small scale.
 *
 * <p>{@link #baseSourceChangePropagatesToSubclassesEvenWhenBaseIsUnderTestRoot} specifically
 * pins down a real bug caught while building this feature: a shared test-source base class
 * (this project's actual {@code BaseTest}/{@code KeywordTestBase}/{@code MobileBaseTest} all live
 * under {@code src/test/java}, not {@code src/main/java}) has to seed the reverse-dependency
 * closure exactly like a changed main-source class does, or every one of its subclasses is
 * silently missed.
 */
class TestImpactAnalyzerIntegrationTest {

    @TempDir
    Path repo;

    @BeforeEach
    void initRepo() throws IOException, InterruptedException {
        git("init", "-q");
        git("config", "user.email", "tia-test@example.com");
        git("config", "user.name", "tia-test");
        TiaTestFixtures.write(repo, ".gitignore", "target/\n");
    }

    private void git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).inheritIO().start();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }

    private void commit(String message) throws IOException, InterruptedException {
        git("add", "-A");
        git("commit", "-q", "-m", message);
    }

    private void compileAll() {
        TiaTestFixtures.compile(repo.resolve("src/main/java"), repo.resolve("target/classes"));
        TiaTestFixtures.compile(repo.resolve("src/test/java"), repo.resolve("target/test-classes"),
            List.of(repo.resolve("target/classes")));
    }

    private ImpactResult analyze(String base) throws IOException, InterruptedException {
        TestImpactAnalyzer analyzer = new TestImpactAnalyzer(
            repo, List.of(repo.resolve("target/classes"), repo.resolve("target/test-classes")), true);
        return analyzer.analyze(base, null);
    }

    private void seedBaseFixture() {
        TiaTestFixtures.write(repo, "src/main/java/com/automation/core/util/StringHelper.java",
            "package com.automation.core.util;\npublic class StringHelper { public static String shout(String s) { return s.toUpperCase(); } }\n");
        TiaTestFixtures.write(repo, "src/main/java/com/automation/core/config/ConfigReader.java",
            "package com.automation.core.config;\npublic class ConfigReader { public static String site() { return System.getProperty(\"site\", \"demoqa\"); } }\n");
        TiaTestFixtures.write(repo, "src/test/java/com/automation/sites/core/BaseTest.java",
            "package com.automation.sites.core;\npublic abstract class BaseTest { protected void setUp() { } }\n");
        TiaTestFixtures.write(repo, "src/test/java/com/automation/sites/demoqa/tests/ButtonsTest.java",
            "package com.automation.sites.demoqa.tests;\n"
                + "import com.automation.core.util.StringHelper;\n"
                + "import com.automation.sites.core.BaseTest;\n"
                + "public class ButtonsTest extends BaseTest { void t() { StringHelper.shout(\"hi\"); } }\n");
        TiaTestFixtures.write(repo, "src/test/java/com/automation/sites/saucedemo/tests/LoginTest.java",
            "package com.automation.sites.saucedemo.tests;\n"
                + "import com.automation.core.config.ConfigReader;\n"
                + "import com.automation.sites.core.BaseTest;\n"
                + "public class LoginTest extends BaseTest {\n"
                + "  private static final String DATA = \"src/test/resources/testdata/login.csv\";\n"
                + "  void t() { ConfigReader.site(); }\n"
                + "}\n");
        TiaTestFixtures.write(repo, "src/test/resources/testdata/login.csv", "user,pass\nstandard_user,secret\n");
        TiaTestFixtures.write(repo, "src/test/resources/config/demoqa.properties", "url=https://demoqa.com\n");
    }

    @Test
    void mainClassChangeImpactsOnlyItsDependentTest() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "src/main/java/com/automation/core/util/StringHelper.java",
            "package com.automation.core.util;\npublic class StringHelper { public static String shout(String s) { return s.toUpperCase() + \"!\"; } }\n");
        compileAll();

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        assertEquals(Set.of("com.automation.sites.demoqa.tests.ButtonsTest"), result.impactedTests().keySet());
    }

    @Test
    void baseSourceChangePropagatesToSubclassesEvenWhenBaseIsUnderTestRoot() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "src/test/java/com/automation/sites/core/BaseTest.java",
            "package com.automation.sites.core;\npublic abstract class BaseTest { protected void setUp() { System.out.println(\"setup\"); } }\n");
        compileAll();

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        assertEquals(Set.of(
            "com.automation.sites.demoqa.tests.ButtonsTest",
            "com.automation.sites.saucedemo.tests.LoginTest"
        ), result.impactedTests().keySet());
        // The abstract base itself must never appear in the "tests to run" set —
        // it has no @Test methods of its own.
        assertTrue(result.impactedTests().keySet().stream().noneMatch(f -> f.endsWith("BaseTest")));
    }

    @Test
    void resourceChangeWithLiteralReferenceImpactsOnlyThatTest() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "src/test/resources/testdata/login.csv", "user,pass\nstandard_user,secret123\n");

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        assertEquals(Set.of("com.automation.sites.saucedemo.tests.LoginTest"), result.impactedTests().keySet());
    }

    @Test
    void resourceChangeWithNoLiteralReferenceFallsBackToSite() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "src/test/resources/config/demoqa.properties", "url=https://demoqa.com/\n");

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        assertEquals(Set.of("com.automation.sites.demoqa.tests.ButtonsTest"), result.impactedTests().keySet());
    }

    @Test
    void newUntrackedTestFileIsIncludedEvenWithoutBeingCommitted() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "src/test/java/com/automation/sites/demoqa/tests/NewFeatureTest.java",
            "package com.automation.sites.demoqa.tests;\npublic class NewFeatureTest { void t() { } }\n");
        // Deliberately not compiled and not committed — simulates a developer who just saved the file.

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        assertTrue(result.impactedTests().containsKey("com.automation.sites.demoqa.tests.NewFeatureTest"));
    }

    @Test
    void buildFileChangeForcesFullSuite() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "pom.xml", "<project/>\n");

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.FULL, result.mode());
        assertTrue(result.unsafeReasons().stream().anyMatch(r -> r.contains("pom.xml")));
    }

    @Test
    void noChangesMeansNoImpactedTests() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        assertTrue(result.impactedTests().isEmpty());
    }

    @Test
    void coverageMapCatchesDependencyTheBytecodeGraphCannotSee() throws Exception {
        seedBaseFixture();
        compileAll();
        commit("base");

        // Simulate what com.automation.core.coverage.CoverageMapBuilder would have produced
        // from a real JaCoCo capture run: LoginTest was OBSERVED, at runtime, to execute
        // StringHelper — even though (per the fixture) nothing in LoginTest's or its
        // dependencies' compiled bytecode statically references StringHelper at all. This is
        // exactly the reflection/dynamic-dispatch case DependencyGraph alone can't trace.
        TiaTestFixtures.write(repo, "target/tia/coverage-map.txt",
            "com.automation.sites.saucedemo.tests.LoginTest\tcom.automation.core.util.StringHelper\n");

        TiaTestFixtures.write(repo, "src/main/java/com/automation/core/util/StringHelper.java",
            "package com.automation.core.util;\npublic class StringHelper { public static String shout(String s) { return s.toUpperCase() + \"!\"; } }\n");
        compileAll();

        ImpactResult result = analyze("HEAD");

        assertEquals(ImpactResult.Mode.IMPACTED, result.mode());
        // Bytecode graph alone would only find ButtonsTest (see
        // mainClassChangeImpactsOnlyItsDependentTest) — the coverage map adds LoginTest too.
        assertEquals(Set.of(
            "com.automation.sites.demoqa.tests.ButtonsTest",
            "com.automation.sites.saucedemo.tests.LoginTest"
        ), result.impactedTests().keySet());
        assertTrue(result.impactedTests().get("com.automation.sites.saucedemo.tests.LoginTest").stream()
            .anyMatch(detail -> detail.reason() == ImpactReason.COVERAGE_OBSERVED_DEPENDENCY));
    }

    @Test
    void missingCoverageMapChangesNothing() throws Exception {
        // No target/tia/coverage-map.txt written at all — must behave identically to a run
        // where coverage-based fallback was never opted into.
        seedBaseFixture();
        compileAll();
        commit("base");

        TiaTestFixtures.write(repo, "src/main/java/com/automation/core/util/StringHelper.java",
            "package com.automation.core.util;\npublic class StringHelper { public static String shout(String s) { return s.toUpperCase() + \"!\"; } }\n");
        compileAll();

        ImpactResult result = analyze("HEAD");

        assertEquals(Set.of("com.automation.sites.demoqa.tests.ButtonsTest"), result.impactedTests().keySet());
    }
}
