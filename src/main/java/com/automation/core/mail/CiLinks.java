package com.automation.core.mail;

import java.util.Map;

/** Finds a link to the current CI run from the environment variables GitHub Actions, Jenkins and GitLab set. */
public final class CiLinks {

    private CiLinks() {
    }

    public static String fromEnv(Map<String, String> env) {
        String ghServer = env.get("GITHUB_SERVER_URL");
        String ghRepo = env.get("GITHUB_REPOSITORY");
        String ghRun = env.get("GITHUB_RUN_ID");
        if (notBlank(ghServer) && notBlank(ghRepo) && notBlank(ghRun)) {
            return ghServer + "/" + ghRepo + "/actions/runs/" + ghRun;
        }
        if (notBlank(env.get("BUILD_URL"))) {
            return env.get("BUILD_URL");
        }
        if (notBlank(env.get("CI_PIPELINE_URL"))) {
            return env.get("CI_PIPELINE_URL");
        }
        return "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
