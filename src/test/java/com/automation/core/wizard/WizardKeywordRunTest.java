package com.automation.core.wizard;

import com.automation.core.keyword.KeywordEngine;
import com.automation.core.keyword.KeywordReader;
import com.automation.core.keyword.KeywordStep;
import com.automation.core.keyword.ObjectRepository;
import com.automation.sites.core.BaseTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Generic runner for UI scenarios built in the dashboard's test wizard
 * (Scripts/dashboard/wizard). The wizard's UI converter writes a keyword script (CSV) and an
 * object repository (.properties) into the run's own workspace folder and passes their paths
 * as system properties; this class just replays them through the normal KeywordEngine, so a
 * wizard scenario behaves exactly like a hand-written keyword-driven test.
 *
 * <ul>
 *   <li>{@code wizard.keyword.repo}   - path to the generated object repository</li>
 *   <li>{@code wizard.keyword.script} - path to the generated keyword script CSV</li>
 *   <li>{@code wizard.keyword.cases}  - comma-separated testCase names to run</li>
 * </ul>
 *
 * Only reachable through a generated suite XML (group "wizard"); it is in no checked-in suite,
 * so it never runs in CI by itself.
 */
public class WizardKeywordRunTest extends BaseTest {

    @DataProvider(name = "wizardCases")
    public Object[][] wizardCases() {
        String cases = System.getProperty("wizard.keyword.cases", "");
        String[] names = cases.isBlank() ? new String[0] : cases.split("\\s*,\\s*");
        Object[][] data = new Object[names.length][1];
        for (int i = 0; i < names.length; i++) {
            data[i][0] = names[i];
        }
        return data;
    }

    @Test(dataProvider = "wizardCases", groups = {"wizard"},
        description = "Wizard-generated keyword scenario")
    public void runWizardScenario(String testCase) {
        String repoPath = System.getProperty("wizard.keyword.repo");
        String scriptPath = System.getProperty("wizard.keyword.script");
        if (repoPath == null || scriptPath == null) {
            throw new IllegalStateException(
                "wizard.keyword.repo and wizard.keyword.script must be set (see Scripts/dashboard/wizard)");
        }
        ObjectRepository repo = ObjectRepository.fromFile(Path.of(repoPath));
        List<KeywordStep> steps = KeywordReader.readTestCase(scriptPath, testCase);
        new KeywordEngine(getDriver(), repo).run(steps);
    }
}
