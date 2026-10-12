package com.automation.core.api.generic;

import com.automation.core.config.ConfigReader;
import com.automation.core.contract.Contract;
import com.automation.core.contract.Contract.Interaction;
import com.automation.core.contract.ContractVerifier;
import com.automation.core.contract.ProviderStateHandler;
import com.automation.core.contract.RestAssuredExchange;
import com.automation.sites.core.BaseApiTest;
import io.qameta.allure.Allure;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider verification: replays every interaction of every contract in {@code contract.dir}
 * against the active site's API and fails - naming the consumer and the exact field - when the
 * provider no longer honours what a consumer relies on. One test per interaction.
 *
 * <p>Run: {@code mvn test -Dsite=<api-site> -DsuiteXmlFile=testng-suites/api-contracts.xml}
 *
 * <pre>
 * contract.dir            = src/test/resources/contracts   *.json Pact files (v2 / v3 layout)
 * contract.provider       =                                only contracts whose provider name equals this (blank = all)
 * contract.state.handler  =                                class implementing ProviderStateHandler (blank = none)
 * </pre>
 * See {@link Contract} for what is supported, and docs/testing-guide.md for how consumers
 * publish a contract and use {@code ContractStubServer} for their own side.
 */
public class ContractVerificationRunner extends BaseApiTest {

    @DataProvider(name = "interactions")
    public Object[][] interactions() throws IOException {
        Path dir = Path.of(ConfigReader.get("contract.dir", "src/test/resources/contracts"));
        String provider = ConfigReader.get("contract.provider", "").trim();
        List<Object[]> rows = new ArrayList<>();
        for (Contract contract : Contract.loadAll(dir)) {
            if (!provider.isEmpty() && !contract.provider().equalsIgnoreCase(provider)) {
                continue;
            }
            if (!contract.validate().isEmpty()) {
                rows.add(new Object[] {contract, null}); // surfaces as a FAILED test, never a silent pass
                continue;
            }
            for (Interaction interaction : contract.interactions()) {
                rows.add(new Object[] {contract, interaction});
            }
        }
        if (rows.isEmpty()) {
            return new Object[][] {{null, null}};
        }
        return rows.toArray(new Object[0][]);
    }

    @Test(dataProvider = "interactions", groups = {"contract", "api"},
        description = "API - Provider honours consumer contract")
    public void providerHonoursContract(Contract contract, Interaction interaction) throws Exception {
        if (contract == null) {
            throw new SkipException("No contracts found in "
                + Path.of(ConfigReader.get("contract.dir", "src/test/resources/contracts")).toAbsolutePath()
                + " - add Pact JSON files there (or set contract.dir / contract.provider)");
        }
        if (interaction == null) {
            Assert.fail("Contract file " + contract.source() + " is not usable:\n - "
                + String.join("\n - ", contract.validate()));
        }
        Allure.parameter("consumer", contract.consumer());
        Allure.parameter("provider", contract.provider());
        Allure.parameter("interaction", interaction.description());

        ContractVerifier.Result result = ContractVerifier.verify(interaction, new RestAssuredExchange(), stateHandler());
        Assert.assertTrue(result.passed(), "Contract '" + contract.consumer() + " -> " + contract.provider()
            + "' (" + contract.source() + ") is broken at interaction '" + interaction.description() + "':\n - "
            + String.join("\n - ", result.mismatches()));
    }

    private static ProviderStateHandler stateHandler() throws ReflectiveOperationException {
        String className = ConfigReader.get("contract.state.handler", "").trim();
        if (className.isEmpty()) {
            return null;
        }
        return (ProviderStateHandler) Class.forName(className).getDeclaredConstructor().newInstance();
    }
}
