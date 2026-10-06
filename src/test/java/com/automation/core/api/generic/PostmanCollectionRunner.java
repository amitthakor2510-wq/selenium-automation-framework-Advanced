package com.automation.core.api.generic;

import com.automation.core.api.postman.NewmanRunner;
import com.automation.core.config.ConfigReader;
import io.qameta.allure.Allure;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs every exported Postman collection in a folder through Newman - one TestNG test
 * per collection, each failing if any Postman assertion fails. Run with
 * testng-suites/api-postman.xml.
 *
 * How to use: in Postman, export the collection (v2.1) and, if you use one, the
 * environment, and drop both files into src/test/resources/postman/.
 *
 *   postman.collections.dir  = src/test/resources/postman   folder with *.postman_collection.json
 *   postman.collection       =                              only run collections whose file name contains this
 *   postman.environment      =                              environment file name; blank = the only *.postman_environment.json, if exactly one
 *   postman.baseUrlVariable  =                              if set (e.g. baseUrl), the site's url is passed to Postman as that variable
 *   postman.report.dir       = target/postman-reports       where the HTML/JSON/console reports go
 *
 * See NewmanRunner for the remaining postman.* keys.
 */
public class PostmanCollectionRunner {

    @BeforeClass(alwaysRun = true)
    public void resetConfig() {
        ConfigReader.reset();
    }

    private static Path dir() {
        return Path.of(ConfigReader.get("postman.collections.dir", "src/test/resources/postman"));
    }

    private static List<Path> filesEndingWith(Path dir, String suffix) throws IOException {
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(suffix)).sorted().forEach(found::add);
            }
        }
        return found;
    }

    @DataProvider(name = "collections")
    public Object[][] collections() throws IOException {
        String only = ConfigReader.get("postman.collection", "").trim().toLowerCase();
        List<Path> collections = filesEndingWith(dir(), ".postman_collection.json").stream()
            .filter(p -> only.isEmpty() || p.getFileName().toString().toLowerCase().contains(only))
            .toList();
        if (collections.isEmpty()) {
            return new Object[][] {{null}};
        }
        Object[][] rows = new Object[collections.size()][1];
        for (int i = 0; i < collections.size(); i++) {
            rows[i][0] = collections.get(i);
        }
        return rows;
    }

    private static Path environment() throws IOException {
        String configured = ConfigReader.get("postman.environment", "").trim();
        if (!configured.isEmpty()) {
            Path asGiven = Path.of(configured);
            return Files.exists(asGiven) ? asGiven : dir().resolve(configured);
        }
        List<Path> envs = filesEndingWith(dir(), ".postman_environment.json");
        return envs.size() == 1 ? envs.get(0) : null;
    }

    @Test(dataProvider = "collections", groups = {"postman", "api"},
        description = "API - Run Postman collection via Newman")
    public void runCollection(Path collection) throws IOException {
        if (collection == null) {
            throw new SkipException("No *.postman_collection.json found in " + dir().toAbsolutePath()
                + " - export a collection from Postman into that folder");
        }
        if (!NewmanRunner.isInstalled()) {
            Assert.fail("Newman is not installed (or not on PATH). Run: npm install -g newman newman-reporter-htmlextra"
                + "  - or set postman.newman.path to the newman executable.");
        }
        Allure.parameter("collection", collection.getFileName().toString());

        Map<String, String> envVars = new LinkedHashMap<>();
        String baseUrlVariable = ConfigReader.get("postman.baseUrlVariable", "").trim();
        if (!baseUrlVariable.isEmpty()) {
            envVars.put(baseUrlVariable, ConfigReader.get("url"));
        }

        NewmanRunner.Result result = NewmanRunner.run(collection, environment(), envVars,
            Path.of(ConfigReader.get("postman.report.dir", "target/postman-reports")));

        if (Files.exists(result.htmlReport())) {
            Allure.addAttachment("Postman report (HTML)", "text/html", Files.readString(result.htmlReport()), ".html");
        }
        Allure.addAttachment("Newman console output", "text/plain", Files.readString(result.consoleLog()), ".txt");

        Assert.assertTrue(result.passed(), collection.getFileName() + ": " + result.summary()
            + "\nFailures:\n - " + String.join("\n - ", result.failures())
            + "\nFull report: " + result.htmlReport().toAbsolutePath());
    }
}
