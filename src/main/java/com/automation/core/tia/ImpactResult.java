package com.automation.core.tia;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The full outcome of one TIA run — enough to write every report format and drive CI. */
public final class ImpactResult {

    public enum Mode { IMPACTED, FULL }

    private final Mode mode;
    private final List<String> unsafeReasons;
    /** Impacted test FQCN -> the reason(s) it's impacted, in the order they were discovered. */
    private final Map<String, List<ImpactReasonDetail>> impactedTests;
    private final int totalTestClassesInProject;
    private final List<ChangedFile> changedFiles;

    public ImpactResult(Mode mode, List<String> unsafeReasons, Map<String, List<ImpactReasonDetail>> impactedTests,
                         int totalTestClassesInProject, List<ChangedFile> changedFiles) {
        this.mode = mode;
        this.unsafeReasons = unsafeReasons;
        this.impactedTests = new TreeMap<>(impactedTests);
        this.totalTestClassesInProject = totalTestClassesInProject;
        this.changedFiles = changedFiles;
    }

    public Mode mode() {
        return mode;
    }

    public List<String> unsafeReasons() {
        return unsafeReasons;
    }

    public Map<String, List<ImpactReasonDetail>> impactedTests() {
        return impactedTests;
    }

    public int totalTestClassesInProject() {
        return totalTestClassesInProject;
    }

    public List<ChangedFile> changedFiles() {
        return changedFiles;
    }

    /** One human-readable explanation of why a test is in the impacted set. */
    public record ImpactReasonDetail(ImpactReason reason, String detail) {
        @Override
        public String toString() {
            return detail == null || detail.isBlank() ? reason.name() : reason.name() + ": " + detail;
        }
    }
}
