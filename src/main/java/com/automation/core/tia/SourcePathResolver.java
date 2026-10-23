package com.automation.core.tia;

import java.util.Optional;

/** Converts between repo-relative {@code .java} source paths and fully-qualified class names. */
public final class SourcePathResolver {

    public static final String MAIN_ROOT = "src/main/java/";
    public static final String TEST_ROOT = "src/test/java/";

    private SourcePathResolver() {
    }

    public enum Root { MAIN, TEST, NONE }

    public static Root rootOf(String repoRelativePath) {
        if (repoRelativePath.startsWith(MAIN_ROOT)) {
            return Root.MAIN;
        }
        if (repoRelativePath.startsWith(TEST_ROOT)) {
            return Root.TEST;
        }
        return Root.NONE;
    }

    /**
     * Converts e.g. {@code "src/test/java/com/automation/sites/demoqa/tests/ButtonsTest.java"}
     * to {@code "com.automation.sites.demoqa.tests.ButtonsTest"}. Empty if the path isn't a
     * {@code .java} file under a recognized source root.
     */
    public static Optional<String> toFqcn(String repoRelativePath) {
        if (!repoRelativePath.endsWith(".java")) {
            return Optional.empty();
        }
        Root root = rootOf(repoRelativePath);
        String prefix = switch (root) {
            case MAIN -> MAIN_ROOT;
            case TEST -> TEST_ROOT;
            case NONE -> null;
            default -> throw new IllegalStateException("Unreachable — Root has exactly these three values.");
        };
        if (prefix == null) {
            return Optional.empty();
        }
        String rel = repoRelativePath.substring(prefix.length());
        rel = rel.substring(0, rel.length() - ".java".length());
        if (rel.isEmpty() || rel.contains("//")) {
            return Optional.empty();
        }
        return Optional.of(rel.replace('/', '.'));
    }

    /** The simple (unqualified) class name a source file is expected to declare, from its filename. */
    public static String simpleNameOf(String repoRelativeJavaPath) {
        String fileName = repoRelativeJavaPath.substring(repoRelativeJavaPath.lastIndexOf('/') + 1);
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
    }
}
