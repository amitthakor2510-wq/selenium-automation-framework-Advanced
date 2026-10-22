package com.automation.core.mail;

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
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds a {@link RunSummary} from TestNG's {@code testng-results.xml} files (Surefire writes one per
 * run under {@code target/surefire-reports}). This is what lets a CI job send ONE consolidated e-mail
 * for a whole pipeline instead of one per site/browser JVM.
 *
 * <p>A SKIP that is only an earlier attempt of a test that later passed or failed (a retry) is not
 * counted, so the totals match what a person would call "the tests".
 */
public final class TestNgResultsParser {

    private TestNgResultsParser() {
    }

    /** Finds every {@code testng-results.xml} below {@code dir} and aggregates them. */
    public static RunSummary parseDirectory(Path dir, String site, String browser) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().equals("testng-results.xml"))
                    .filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        return parseFiles(files, site, browser);
    }

    public static RunSummary parseFiles(List<Path> files, String site, String browser) throws IOException {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long durationMs = 0;
        List<RunSummary.FailedTest> failures = new ArrayList<>();
        Set<String> suites = new HashSet<>();
        String firstSuite = "";
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
            NodeList classes = doc.getElementsByTagName("class");
            Set<String> ranToCompletion = new HashSet<>();
            List<Element> skips = new ArrayList<>();
            List<String> skipClass = new ArrayList<>();
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
                            failed++;
                            failures.add(failure(className, m));
                        }
                        continue;
                    }
                    switch (status) {
                        case "PASS" -> {
                            passed++;
                            ranToCompletion.add(key(className, m));
                        }
                        case "FAIL" -> {
                            failed++;
                            failures.add(failure(className, m));
                            ranToCompletion.add(key(className, m));
                        }
                        case "SKIP" -> {
                            skips.add(m);
                            skipClass.add(className);
                        }
                        default -> {
                            // Other statuses (e.g. SUCCESS_PERCENTAGE_FAILURE) carry no counted outcome here.
                        }
                    }
                }
            }
            for (int i = 0; i < skips.size(); i++) {
                if (!ranToCompletion.contains(key(skipClass.get(i), skips.get(i)))) {
                    skipped++;
                }
            }
        }
        String suiteName = suites.size() > 1 ? suites.size() + " suites" : firstSuite;
        return new RunSummary(site, browser, suiteName, passed + failed + skipped, passed, failed, skipped,
            durationMs, Instant.now(), failures, "", null);
    }

    private static RunSummary.FailedTest failure(String className, Element method) {
        String message = "";
        NodeList ex = method.getElementsByTagName("exception");
        if (ex.getLength() > 0) {
            Element e = (Element) ex.item(0);
            NodeList msg = e.getElementsByTagName("message");
            String text = msg.getLength() > 0 ? msg.item(0).getTextContent() : "";
            message = e.getAttribute("class") + (text.isBlank() ? "" : ": " + text);
        }
        return new RunSummary.FailedTest(method.getAttribute("name"), simpleName(className),
            EmailReportBuilder.sanitizeMessage(message));
    }




}
