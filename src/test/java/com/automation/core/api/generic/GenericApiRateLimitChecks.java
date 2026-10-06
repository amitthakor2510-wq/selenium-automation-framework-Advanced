package com.automation.core.api.generic;

import com.automation.core.api.ApiClient;
import com.automation.core.api.ApiRateLimit;
import com.automation.core.api.auth.RefreshingBearerAuthProvider;
import com.automation.core.config.ConfigReader;
import com.automation.sites.core.BaseApiTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Config-driven rate-limit checks. Run with testng-suites/api-rate-limit.xml.
 *
 *   api.ratelimit.path          = /orders     endpoint to hit (blank = every test here is skipped)
 *   api.ratelimit.requests      = 30          requests per burst
 *   api.ratelimit.threads       = 5           parallel workers
 *   api.ratelimit.expectThrottle = true       true: the burst must hit HTTP 429; false: it must stay under the limit
 *   api.ratelimit.recoveryWaitMs = 0          if > 0, wait this long after the burst and expect requests to work again
 *   api.auth.*                  = optional, see RefreshingBearerAuthProvider.fromConfig
 *
 * This is real load on the target - only use it where you are allowed to.
 */
public class GenericApiRateLimitChecks extends BaseApiTest {

    private static String path() {
        String path = ConfigReader.get("api.ratelimit.path", "").trim();
        if (path.isEmpty()) {
            throw new SkipException("Set api.ratelimit.path in config/" + ConfigReader.getActiveSite() + ".properties to enable rate-limit checks");
        }
        return path;
    }

    private static Supplier<Response> call(String path) {
        Optional<RefreshingBearerAuthProvider> auth = RefreshingBearerAuthProvider.fromConfig();
        auth.ifPresent(RefreshingBearerAuthProvider::currentToken); // fetch the token once, before the burst
        return () -> {
            RequestSpecification spec = ApiClient.request();
            if (auth.isPresent()) {
                spec = auth.get().apply(spec);
            }
            return spec.when().get(path);
        };
    }

    private static ApiRateLimit.Result runBurst(String path) {
        return ApiRateLimit.burst(call(path),
            ConfigReader.getInt("api.ratelimit.requests", 30),
            ConfigReader.getInt("api.ratelimit.threads", 5));
    }

    @Test(groups = {"ratelimit", "api"},
        description = "Rate limit - a burst is throttled with HTTP 429 (or stays under the limit when expectThrottle=false)")
    public void burst_ShouldMatchExpectedThrottling() {
        ApiRateLimit.Result result = runBurst(path());
        if (ConfigReader.getBoolean("api.ratelimit.expectThrottle", true)) {
            ApiRateLimit.assertThrottled(result);
        } else {
            ApiRateLimit.assertNotThrottled(result);
        }
    }

    @Test(groups = {"ratelimit", "api"},
        description = "Rate limit - throttled responses include a Retry-After header")
    public void throttledResponse_ShouldIncludeRetryAfter() {
        if (!ConfigReader.getBoolean("api.ratelimit.expectThrottle", true)) {
            throw new SkipException("api.ratelimit.expectThrottle=false - no 429 expected");
        }
        ApiRateLimit.assertRetryAfterPresent(runBurst(path()));
    }

    @Test(groups = {"ratelimit", "api"},
        description = "Rate limit - hitting the limit never causes server errors (5xx)")
    public void burst_ShouldNeverCauseServerErrors() {
        ApiRateLimit.assertNoServerErrors(runBurst(path()));
    }

    @Test(groups = {"ratelimit", "api"},
        description = "Rate limit - the API accepts requests again after the window resets")
    public void api_ShouldRecoverAfterWindowResets() {
        String path = path();
        long waitMs = ConfigReader.getInt("api.ratelimit.recoveryWaitMs", 0);
        if (waitMs <= 0) {
            throw new SkipException("Set api.ratelimit.recoveryWaitMs (e.g. 60000) to enable the recovery check");
        }
        runBurst(path);
        ApiRateLimit.assertRecovers(call(path), waitMs);
    }
}
