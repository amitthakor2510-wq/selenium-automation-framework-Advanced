package com.automation.core.report;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;

/**
 * Shared helpers for reading TestNG's {@code testng-results.xml}: used by the e-mail summary
 * ({@code TestNgResultsParser}) and the PDF report ({@code TestNgXmlReportReader}), so the XXE-hardened
 * parser and the retry-matching key exist in exactly one place.
 */
public final class TestNgXml {

    private TestNgXml() {
    }

    /** Parses the XML with DOCTYPEs and external entities disabled (no XXE). */
    public static Document parse(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("Cannot parse testng-results.xml: " + e.getMessage(), e);
        }
    }

    /** Identity of one test invocation (class, signature, parameter values) - how a retry is matched to its SKIP. */
    public static String key(String className, Element method) {
        StringBuilder params = new StringBuilder();
        NodeList p = method.getElementsByTagName("params");
        for (int i = 0; i < p.getLength(); i++) {
            params.append(p.item(i).getTextContent().replaceAll("\\s+", " ").trim());
        }
        return className + "|" + method.getAttribute("signature") + "|" + params;
    }

    public static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** Lenient long parsing: blank or malformed values count as 0. */
    public static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
