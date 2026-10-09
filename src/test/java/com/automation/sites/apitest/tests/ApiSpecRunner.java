package com.automation.sites.apitest.tests;

import com.automation.core.api.spec.ApiCase;
import com.automation.core.api.spec.ApiRunReport;
import com.automation.core.api.spec.ApiSpecLoader;
import com.automation.core.api.spec.OpenApiSpecGenerator;
import com.automation.core.api.spec.SpecExecutor;
import com.automation.core.api.spec.SpecFile;
import com.automation.core.config.ConfigReader;
import com.automation.sites.core.BaseApiTest;
import io.qameta.allure.Allure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITest;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NO-CODE API TESTING. Reads request/expectation files (YAML, JSON or CSV) - or an OpenAPI/Swagger
 * document - and runs every request as its own TestNG test, then writes
 * {@code target/api-report/index.html} (one self-contained page) next to the usual Extent/Allure reports.
 *
 * <pre>
 *   ./Scripts/api-test.sh --openapi https://host/v3/api-docs
 *   ./Scripts/api-test.sh --spec src/test/resources/apitests/my-api.yml --base-url https://host
 *   mvn test -Dsite=apitest -DsuiteXmlFile=testng-suites/api-spec.xml     (what the script runs)
 * </pre>
 *
 * Keys (all optional; set in config/apitest.properties or pass as -D):
 * <pre>
 *   api.spec.dir        folder with spec files                      (src/test/resources/apitests)
 *   api.spec.file       only run spec files whose name contains this
 *   api.spec.tags       only run cases with one of these tags       (smoke,regression)
 *   api.spec.excludeTags  skip cases with one of these tags
 *   api.spec.name       only run cases whose name contains this
 *   api.openapi         OpenAPI/Swagger URL or file - generates the tests on the fly
 *   api.openapi.negative  true = also generate "no credentials" / "empty body" negative tests
 *   api.env             environment name from the spec's 'environments:' block
 *   api.base.url        overrides the base URL of every spec
 *   api.auth.token      bearer token used when a case has no 'auth:' of its own
 *   api.var.NAME        sets variable ${NAME}
 *   api.spec.maxTimeMs  default response-time limit for every case
 * </pre>
 * Classes of requests run in file order, sequentially: later requests can use values extracted from
 * earlier ones (see docs/API_NO_CODE_GUIDE.md).
 */
public class ApiSpecRunner extends BaseApiTest implements ITest {

    private static final Logger log = LoggerFactory.getLogger(ApiSpecRunner.class);
    private static final ApiRunReport REPORT = new ApiRunReport();
    private static final SpecExecutor EXECUTOR = new SpecExecutor(REPORT);
    private static final List<SpecFile> LOADED = new ArrayList<>();
    private static final String NO_CASES = "NO_CASES";

    private final ThreadLocal<String> testName = new ThreadLocal<>();

    @Override
    public String getTestName() {
        return testName.get();
    }

    @BeforeMethod(alwaysRun = true)
    public void nameTheTest(Method method, Object[] parameters) {
        String name = parameters != null && parameters.length > 0 && parameters[0] instanceof ApiCase c
            ? c.displayName() : method.getName();
        testName.set(name);
    }

    // ------------------------------------------------------------------------------------

    @DataProvider(name = "apiCases")
    public Object[][] apiCases() throws IOException, InterruptedException {
        ConfigReader.reset();
        List<Path> files = new ArrayList<>();

        String openapi = ConfigReader.get("api.openapi", "").trim();
        if (!openapi.isEmpty()) {
            boolean negative = ConfigReader.getBoolean("api.openapi.negative", false);
            OpenApiSpecGenerator.Result generated = OpenApiSpecGenerator.generateFromLocation(openapi, negative);
            Path out = Path.of("target", "generated-apitests", "openapi-generated.yml");
            Files.createDirectories(out.getParent());
            Files.writeString(out, generated.yaml(), StandardCharsets.UTF_8);
            log.info("[api-spec] generated {} operations from {} -> {}", generated.operations(), openapi, out);
            generated.notes().forEach(n -> log.info("[api-spec] note: {}", n));
            files.add(out);
        }

        String explicit = ConfigReader.get("api.spec", "").trim();
        if (!explicit.isEmpty()) {
            for (String part : explicit.split(",")) {
                if (!part.isBlank()) {
                    files.add(Path.of(part.trim()));
                }
            }
        } else if (openapi.isEmpty() || ConfigReader.getBoolean("api.spec.includeDir", false)) {
            files.addAll(ApiSpecLoader.findSpecFiles(Path.of(ConfigReader.get("api.spec.dir", "src/test/resources/apitests"))));
        }

        String fileFilter = ConfigReader.get("api.spec.file", "").trim().toLowerCase(Locale.ROOT);
        String environment = ConfigReader.get("api.env", "").trim();
        Map<String, Object> overrides = new LinkedHashMap<>();
        System.getProperties().forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.startsWith("api.var.")) {
                overrides.put(key.substring("api.var.".length()), String.valueOf(v));
            }
        });
        Set<String> include = csv(ConfigReader.get("api.spec.tags", ""));
        Set<String> exclude = csv(ConfigReader.get("api.spec.excludeTags", ""));
        String nameFilter = ConfigReader.get("api.spec.name", "").trim().toLowerCase(Locale.ROOT);

        List<Object[]> rows = new ArrayList<>();
        synchronized (LOADED) {
            LOADED.clear();
            for (Path file : files) {
                if (!fileFilter.isEmpty() && !file.getFileName().toString().toLowerCase(Locale.ROOT).contains(fileFilter)) {
                    continue;
                }
                SpecFile spec = ApiSpecLoader.load(file, environment, overrides);
                LOADED.add(spec);
                for (ApiCase c : spec.cases()) {
                    if (!include.isEmpty() && c.tags().stream().noneMatch(include::contains)) {
                        continue;
                    }
                    if (!exclude.isEmpty() && c.tags().stream().anyMatch(exclude::contains)) {
                        continue;
                    }
                    if (!nameFilter.isEmpty() && !c.name().toLowerCase(Locale.ROOT).contains(nameFilter)) {
                        continue;
                    }
                    rows.add(new Object[] {c});
                }
            }
        }
        if (rows.isEmpty()) {
            return new Object[][] {{NO_CASES}};
        }
        return rows.toArray(new Object[0][]);
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Test(dataProvider = "apiCases", groups = {"api", "apispec"}, description = "API - request from spec file")
    public void runCase(Object caseOrMarker) {
        if (caseOrMarker instanceof String) {
            throw new SkipException("No API tests found. Put a *.yml/*.json/*.csv spec in "
                + ConfigReader.get("api.spec.dir", "src/test/resources/apitests")
                + ", or pass -Dapi.openapi=<swagger-url> / -Dapi.spec=<file>. "
                + "Check -Dapi.spec.tags / api.spec.file / api.spec.name filters too.");
        }
        String name = ((ApiCase) caseOrMarker).displayName();
        try {
            // the Allure test case exists once the test method is running (not yet in @BeforeMethod)
            Allure.getLifecycle().updateTestCase(tc -> tc.setName(name));
        } catch (RuntimeException ignored) {
            // naming is cosmetic
        }
        EXECUTOR.run((ApiCase) caseOrMarker);
    }

    @AfterClass(alwaysRun = true)
    public void finishRun() {
        List<SpecFile> specs;
        synchronized (LOADED) {
            specs = new ArrayList<>(LOADED);
        }
        int leftovers = EXECUTOR.runCleanups(specs);
        try {
            Path html = REPORT.write(Path.of(ConfigReader.get("api.report.dir", "target/api-report")), "API test report");
            String line = "API results: " + REPORT.count(ApiRunReport.Outcome.PASSED) + " passed, "
                + REPORT.count(ApiRunReport.Outcome.FAILED) + " failed, "
                + REPORT.count(ApiRunReport.Outcome.SKIPPED) + " skipped"
                + (leftovers > 0 ? " (" + leftovers + " cleanup request(s) failed)" : "");
            System.out.println();
            System.out.println("==============================================================");
            System.out.println(" " + line);
            System.out.println(" Report: " + html.toAbsolutePath());
            System.out.println("==============================================================");
        } catch (IOException e) {
            log.warn("[api-spec] could not write the HTML report: {}", e.toString());
        }
    }
}
