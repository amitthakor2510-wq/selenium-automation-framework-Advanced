package com.automation.core.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNgResultsParserTest {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testng-results skipped="2" failed="2" total="6" passed="2">
          <suite name="DemoQA Smoke" duration-ms="4000">
            <test name="t">
              <class name="com.x.LoginTest">
                <test-method status="PASS" signature="ok()[pri:0]" name="ok"/>
                <test-method status="SKIP" signature="flaky()[pri:0]" name="flaky"/>
                <test-method status="PASS" signature="flaky()[pri:0]" name="flaky"/>
                <test-method status="FAIL" signature="bad()[pri:0]" name="bad">
                  <exception class="java.lang.AssertionError"><message><![CDATA[expected password=hunter2 got <b>x</b>]]></message></exception>
                </test-method>
                <test-method status="SKIP" signature="never()[pri:0]" name="never"/>
                <test-method status="FAIL" is-config="true" signature="setUp()" name="setUp">
                  <exception class="java.lang.RuntimeException"><message><![CDATA[boom]]></message></exception>
                </test-method>
                <test-method status="PASS" is-config="true" signature="tearDown()" name="tearDown"/>
              </class>
            </test>
          </suite>
        </testng-results>
        """;

    @Test
    void countsExcludeRetriesAndConfigPasses(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("surefire-reports/testng-results.xml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, XML);
        RunSummary s = TestNgResultsParser.parseDirectory(dir, "demoqa", "chrome");
        assertEquals(2, s.passed(), "ok + the successful second attempt of flaky");
        assertEquals(2, s.failed(), "bad + the failed @BeforeX config method");
        assertEquals(1, s.skipped(), "only 'never' - flaky's SKIP was just its first attempt");
        assertEquals(5, s.total());
        assertEquals("DemoQA Smoke", s.suite());
        assertEquals(4000, s.durationMs());
        assertEquals("demoqa", s.site());
    }

    @Test
    void failuresAreSanitised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("testng-results.xml"), XML);
        RunSummary s = TestNgResultsParser.parseDirectory(dir, "", "");
        RunSummary.FailedTest bad = s.failures().stream().filter(f -> f.name().equals("bad")).findFirst()
            .orElseThrow();
        assertEquals("LoginTest", bad.className());
        assertFalse(bad.message().contains("hunter2"), bad.message());
        assertTrue(bad.message().startsWith("java.lang.AssertionError"), bad.message());
    }

    @Test
    void dataProviderIterationsAreNotCollapsed(@TempDir Path dir) throws Exception {
        String xml = """
            <testng-results><suite name="s" duration-ms="1"><test name="t"><class name="a.B">
              <test-method status="SKIP" signature="m(java.lang.String)" name="m"><params><param index="0"><value><![CDATA[one]]></value></param></params></test-method>
              <test-method status="PASS" signature="m(java.lang.String)" name="m"><params><param index="0"><value><![CDATA[two]]></value></param></params></test-method>
            </class></test></suite></testng-results>
            """;
        Files.writeString(dir.resolve("testng-results.xml"), xml);
        RunSummary s = TestNgResultsParser.parseDirectory(dir, "", "");
        assertEquals(1, s.skipped(), "a skipped iteration is not a retry of a different iteration");
        assertEquals(1, s.passed());
    }

    @Test
    void severalFilesAreAggregated(@TempDir Path dir) throws Exception {
        for (String site : List.of("a", "b")) {
            Path f = dir.resolve(site).resolve("testng-results.xml");
            Files.createDirectories(f.getParent());
            Files.writeString(f, XML.replace("DemoQA Smoke", "Suite " + site));
        }
        RunSummary s = TestNgResultsParser.parseDirectory(dir, "", "");
        assertEquals(4, s.passed());
        assertEquals("2 suites", s.suite());
    }

    @Test
    void emptyOrMissingDirectoryGivesNoTests(@TempDir Path dir) throws Exception {
        assertEquals("NO TESTS", TestNgResultsParser.parseDirectory(dir, "", "").status());
        assertEquals("NO TESTS", TestNgResultsParser.parseDirectory(dir.resolve("nope"), "", "").status());
    }

    @Test
    void doctypeIsRefused(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("testng-results.xml"),
            "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testng-results>&e;</testng-results>");
        assertThrows(IOException.class, () -> TestNgResultsParser.parseDirectory(dir, "", ""));
    }
}
