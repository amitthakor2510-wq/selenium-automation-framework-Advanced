package com.automation.core.report;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared, XXE-hardened testng-results.xml helpers. Pure XML and string logic. */
class TestNgXmlTest {

    @Test
    void parsesWellFormedXml() throws IOException {
        Document doc = TestNgXml.parse("<testng-results total=\"3\"><suite name=\"s\"/></testng-results>");

        assertEquals("testng-results", doc.getDocumentElement().getTagName());
        assertEquals("3", doc.getDocumentElement().getAttribute("total"));
    }

    @Test
    void doctypeDeclarationsAreRefusedSoExternalEntitiesCannotLoad() {
        String xxe = "<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>";

        IOException ex = assertThrows(IOException.class, () -> TestNgXml.parse(xxe));

        assertTrue(ex.getMessage().contains("Cannot parse testng-results.xml"), ex.getMessage());
    }

    @Test
    void malformedXmlIsReportedAsAnIoProblem() {
        IOException ex = assertThrows(IOException.class, () -> TestNgXml.parse("<a>"));

        assertTrue(ex.getMessage().contains("Cannot parse testng-results.xml"), ex.getMessage());
    }

    @Test
    void keyJoinsClassSignatureAndNormalisedParameters() throws IOException {
        Element method = firstMethod("<test-method signature=\"login(java.lang.String)\">"
            + "<params><param index=\"0\"><value>a   b</value></param></params></test-method>");

        assertEquals("com.x.LoginTest|login(java.lang.String)|a b", TestNgXml.key("com.x.LoginTest", method));
    }

    @Test
    void keyOfAMethodWithoutParametersEndsWithAnEmptyParameterPart() throws IOException {
        Element method = firstMethod("<test-method signature=\"smoke()\"/>");

        assertEquals("com.x.T|smoke()|", TestNgXml.key("com.x.T", method));
    }

    @Test
    void sameMethodWithDifferentParametersGetsDifferentKeys() throws IOException {
        Element one = firstMethod("<test-method signature=\"m(java.lang.String)\">"
            + "<params><param index=\"0\"><value>one</value></param></params></test-method>");
        Element two = firstMethod("<test-method signature=\"m(java.lang.String)\">"
            + "<params><param index=\"0\"><value>two</value></param></params></test-method>");

        assertNotEquals(TestNgXml.key("C", one), TestNgXml.key("C", two));
    }

    @Test
    void simpleNameStripsThePackage() {
        assertEquals("LoginTest", TestNgXml.simpleName("com.automation.LoginTest"));
        assertEquals("LoginTest", TestNgXml.simpleName("LoginTest"));
    }

    @Test
    void parseLongIsLenient() {
        assertEquals(42L, TestNgXml.parseLong("42"));
        assertEquals(7L, TestNgXml.parseLong("  7 "));
        assertEquals(0L, TestNgXml.parseLong("not a number"));
        assertEquals(0L, TestNgXml.parseLong(""));
    }

    private static Element firstMethod(String xml) throws IOException {
        Document doc = TestNgXml.parse(xml);
        return doc.getDocumentElement();
    }
}
