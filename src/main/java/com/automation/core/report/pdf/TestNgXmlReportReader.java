package com.automation.core.report.pdf;

import com.automation.core.mail.EmailReportBuilder;
import com.automation.core.report.pdf.PdfReportData.Status;
import com.automation.core.report.pdf.PdfReportData.TestEntry;
import static com.automation.core.report.TestNgXml.key;
import static com.automation.core.report.TestNgXml.parse;
import static com.automation.core.report.TestNgXml.parseLong;
import static com.automation.core.report.TestNgXml.simpleName;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds a {@link PdfReportData} from TestNG's {@code testng-results.xml} files, so a CI job can turn
 * the results of a whole pipeline into one PDF without re-running anything. Counting rules match
 * {@code TestNgResultsParser}: a SKIP that is only an earlier attempt of a test that later ran to
 * completion (a retry) is ignored, and a failed configuration method (a broken {@code @BeforeMethod}
 * and the like) is reported as a failure.
 *
 * <p>Screenshots are not part of that XML, so a PDF built this way has none; the per-run PDF written
 * by the test JVM itself does.
 */
public final class TestNgXmlReportReader {

    private TestNgXmlReportReader() {
    }

    /** Finds every {@code testng-results.xml} below {@code dir} and merges them into one report. */
    public static PdfReportData readDirectory(Path dir, String title, String site, String browser,
                                              int stackTraceLines) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().equals("testng-results.xml"))
                    .filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        return read(files, title, site, browser, stackTraceLines);
    }

    public static PdfReportData read(List<Path> files, String title, String site, String browser,
                                     int stackTraceLines) throws IOException {
        List<TestEntry> tests = new ArrayList<>();
        Set<String> suites = new HashSet<>();
        String firstSuite = "";
        long durationMs = 0;
        for (Path file : files) {
            Document doc = parse(Files.readString(file));
            NodeList suiteNodes = doc.getElementsByTagName("suite");
            for (int i = 0; i < suiteNodes.getLength(); i++) {
                Element suite = (Element) suiteNodes.item(i);
                String name = suite.getAttribute("name");
                if (suites.add(name) && firstSuite.isEmpty()) {
                    firstSuite = name;
                }
                durationMs += parseLong(suite.getAttribute("duration-ms"));
            }
            readTests(doc, tests, stackTraceLines);
        }
        String suiteName = suites.size() > 1 ? suites.size() + " suites" : firstSuite;
        Map<String, String> env = new LinkedHashMap<>();
        if (site != null && !site.isBlank()) {
            env.put("Site", site);
        }
        if (browser != null && !browser.isBlank()) {
            env.put("Browser", browser);
        }
        env.put("Suite", suiteName.isEmpty() ? "(unknown)" : suiteName);
        env.put("Source", files.size() + " testng-results.xml file(s)");
        return new PdfReportData(title, site, browser, suiteName, Instant.now(), durationMs, env,
            PdfReportData.uniqueNames(tests));
    }

    private static void readTests(Document doc, List<TestEntry> out, int stackTraceLines) {
        NodeList classes = doc.getElementsByTagName("class");
        Set<String> ranToCompletion = new HashSet<>();
        List<TestEntry> skips = new ArrayList<>();
        List<String> skipKeys = new ArrayList<>();
        for (int c = 0; c < classes.getLength(); c++) {
            Element cls = (Element) classes.item(c);
            String className = cls.getAttribute("name");
            for (Node n = cls.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element m) || !"test-method".equals(m.getNodeName())) {
                    continue;
                }
                boolean config = "true".equals(m.getAttribute("is-config"));
                String status = m.getAttribute("status");
                if (config) {
                    if ("FAIL".equals(status)) {
                        out.add(entry(className, m, Status.FAILED, stackTraceLines));
                    }
                    continue;
                }
                switch (status) {
                    case "PASS" -> {
                        out.add(entry(className, m, Status.PASSED, stackTraceLines));
                        ranToCompletion.add(key(className, m));
                    }
                    case "FAIL" -> {
                        out.add(entry(className, m, Status.FAILED, stackTraceLines));
                        ranToCompletion.add(key(className, m));
                    }
                    case "SKIP" -> {
                        skips.add(entry(className, m, Status.SKIPPED, stackTraceLines));
                        skipKeys.add(key(className, m));
                    }
                    default -> {
                        // Other statuses carry no counted outcome here.
                    }
                }
            }
        }
        for (int i = 0; i < skips.size(); i++) {
            if (!ranToCompletion.contains(skipKeys.get(i))) {
                out.add(skips.get(i));
            }
        }
    }

    private static TestEntry entry(String className, Element method, Status status, int stackTraceLines) {
        String message = "";
        String stack = "";
        NodeList ex = method.getElementsByTagName("exception");
        if (ex.getLength() > 0) {
            Element e = (Element) ex.item(0);
            NodeList msg = e.getElementsByTagName("message");
            String text = msg.getLength() > 0 ? msg.item(0).getTextContent() : "";
            message = EmailReportBuilder.sanitizeMessage(
                e.getAttribute("class") + (text.isBlank() ? "" : ": " + text));
            NodeList full = e.getElementsByTagName("full-stacktrace");
            if (full.getLength() > 0 && stackTraceLines > 0) {
                stack = condense(full.item(0).getTextContent(), stackTraceLines);
            }
        }
        return new TestEntry(method.getAttribute("name"), simpleName(className), status,
            parseLong(method.getAttribute("duration-ms")), message, stack, null);
    }

    /** Keeps the {@code at ...} / {@code Caused by} lines, drops framework noise, caps the count. */
    private static String condense(String fullStackTrace, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String raw : fullStackTrace.split("\\R")) {
            String line = raw.strip();
            boolean frame = line.startsWith("at ");
            if (!frame && !line.startsWith("Caused by:")) {
                continue;
            }
            if (frame && (line.startsWith("at org.testng.") || line.startsWith("at java.base/jdk.internal.")
                || line.startsWith("at jdk.internal.") || line.startsWith("at java.lang.reflect.")
                || line.startsWith("at org.apache.maven.surefire."))) {
                continue;
            }
            if (lines++ >= maxLines) {
                break;
            }
            sb.append(EmailReportBuilder.sanitizeMessage(line)).append('\n');
        }
        return sb.toString().stripTrailing();
    }




}
