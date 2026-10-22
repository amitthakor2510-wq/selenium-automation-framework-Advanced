package com.automation.core.report.pdf;

import com.automation.core.report.pdf.PdfReportData.Status;
import com.automation.core.report.pdf.PdfReportData.TestEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNgXmlReportReaderTest {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testng-results skipped="2" failed="2" total="6" passed="2">
          <suite name="DemoQA Smoke" duration-ms="4000">
            <test name="t">
              <class name="com.x.LoginTest">
                <test-method status="PASS" signature="ok()[pri:0]" name="ok" duration-ms="120"/>
                <test-method status="SKIP" signature="flaky()[pri:0]" name="flaky"/>
                <test-method status="PASS" signature="flaky()[pri:0]" name="flaky" duration-ms="900"/>
                <test-method status="FAIL" signature="bad()[pri:0]" name="bad" duration-ms="2500">
                  <exception class="java.lang.AssertionError">
                    <message><![CDATA[expected password=hunter2 got <b>x</b>]]></message>
                    <full-stacktrace><![CDATA[java.lang.AssertionError: expected
        \tat com.x.pages.LoginPage.submit(LoginPage.java:42)
        \tat org.testng.internal.invokers.MethodInvocationHelper.invoke(MethodInvocationHelper.java:1)
        \tat com.x.LoginTest.bad(LoginTest.java:17)
        ]]></full-stacktrace>
                  </exception>
                </test-method>
                <test-method status="SKIP" signature="never()[pri:0]" name="never">
                  <exception class="org.testng.SkipException"><message><![CDATA[Quarantined]]></message></exception>
                </test-method>
                <test-method status="FAIL" is-config="true" signature="setUp()" name="setUp">
                  <exception class="java.lang.RuntimeException"><message><![CDATA[boom]]></message></exception>
                </test-method>
                <test-method status="PASS" is-config="true" signature="tearDown()" name="tearDown"/>
              </class>
            </test>
          </suite>
        </testng-results>
        """;

    private static PdfReportData read(Path dir, String xml) throws Exception {
        Path f = dir.resolve("surefire-reports/testng-results.xml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, xml);
        return TestNgXmlReportReader.readDirectory(dir, "Report", "demoqa", "chrome", 10);
    }

    private static TestEntry find(PdfReportData d, String name) {
        return d.tests().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void countsMatchTheEmailParser(@TempDir Path dir) throws Exception {
        PdfReportData d = read(dir, XML);
        assertEquals(2, d.passed(), "ok + the successful second attempt of flaky");
        assertEquals(2, d.failed(), "bad + the failed @BeforeX config method");
        assertEquals(1, d.skipped(), "only 'never' - flaky's SKIP was just its first attempt");
        assertEquals(5, d.total());
        assertEquals("DemoQA Smoke", d.suite());
        assertEquals(4000, d.durationMs());
        assertEquals("demoqa", d.site());
    }

    @Test
    void failureDetailIsRedactedAndCondensed(@TempDir Path dir) throws Exception {
        TestEntry bad = find(read(dir, XML), "bad");
        assertEquals(Status.FAILED, bad.status());
        assertEquals("LoginTest", bad.className());
        assertEquals(2500, bad.durationMs());
        assertFalse(bad.message().contains("hunter2"), bad.message());
        assertTrue(bad.message().startsWith("java.lang.AssertionError"), bad.message());
        assertTrue(bad.stackTrace().contains("LoginPage.submit"), bad.stackTrace());
        assertFalse(bad.stackTrace().contains("org.testng"), "framework frames are dropped");
        assertTrue(bad.screenshot() == null, "the XML has no screenshots");
    }

    @Test
    void skipReasonIsKept(@TempDir Path dir) throws Exception {
        assertTrue(find(read(dir, XML), "never").message().contains("Quarantined"));
    }

    @Test
    void dataDrivenRepeatsGetANumberedSuffix(@TempDir Path dir) throws Exception {
        String xml = """
            <testng-results><suite name="s" duration-ms="1"><test name="t"><class name="a.B">
              <test-method status="PASS" signature="m(java.lang.String)" name="m"><params><param index="0"><value><![CDATA[one]]></value></param></params></test-method>
              <test-method status="PASS" signature="m(java.lang.String)" name="m"><params><param index="0"><value><![CDATA[two]]></value></param></params></test-method>
            </class></test></suite></testng-results>
            """;
        PdfReportData d = read(dir, xml);
        assertEquals("m [#1]", d.tests().get(0).name());
        assertEquals("m [#2]", d.tests().get(1).name());
    }

    @Test
    void noResultsGivesAnEmptyReportNotAnError(@TempDir Path dir) throws Exception {
        PdfReportData d = TestNgXmlReportReader.readDirectory(dir.resolve("missing"), "R", "", "", 10);
        assertEquals("NO TESTS", d.status());
    }

    @Test
    void severalSuiteFilesAreMerged(@TempDir Path dir) throws Exception {
        String one = "<testng-results><suite name=\"A\" duration-ms=\"10\"><test name=\"t\"><class name=\"a.X\">"
            + "<test-method status=\"PASS\" signature=\"p()\" name=\"p\"/></class></test></suite></testng-results>";
        String two = one.replace("name=\"A\"", "name=\"B\"").replace("a.X", "a.Y");
        Files.createDirectories(dir.resolve("one"));
        Files.createDirectories(dir.resolve("two"));
        Files.writeString(dir.resolve("one/testng-results.xml"), one);
        Files.writeString(dir.resolve("two/testng-results.xml"), two);
        PdfReportData d = TestNgXmlReportReader.readDirectory(dir, "R", "", "", 10);
        assertEquals(2, d.total());
        assertEquals("2 suites", d.suite());
        assertEquals(20, d.durationMs());
    }

    @Test
    void endToEndPdfFromXml(@TempDir Path dir) throws Exception {
        PdfReportData d = read(dir, XML);
        Path pdf = PdfReportWriter.write(d, PdfReportSettings.defaults(), dir.resolve("out/consolidated.pdf"));
        assertTrue(Files.size(pdf) > 1000);
    }
}
