package com.automation.core.coverage;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one class in this project that imports {@code org.jacoco.core} directly — see that
 * dependency's comment in {@code pom.xml} for why it's scoped this narrowly. Reads a JaCoCo
 * {@code .exec} file (JaCoCo's own binary execution-data format — the same format
 * {@code target/jacoco.exec} is always written in, and the same format
 * {@code JacocoRuntimeMXBean.getExecutionData(boolean)} returns raw bytes in over JMX) and
 * reports which classes it shows as actually executed.
 *
 * <p>"Executed" here means at least one instrumentation probe fired — {@link ExecutionData}
 * records a {@code boolean[]} per class (one entry per probe JaCoCo inserted into that class's
 * bytecode); a class that was merely loaded but never actually ran any instrumented code (an
 * unused branch, a class referenced but never instantiated) has all-false probes and is not
 * reported as touched. That's deliberately the same bar {@code jacoco:check}'s own line-coverage
 * threshold uses — "executed", not "loaded" or "referenced".
 */
public final class CoverageExecReader {

    private CoverageExecReader() {
    }

    /**
     * The fully-qualified (dotted) names of every class this {@code .exec} file shows at least
     * one hit probe for.
     */
    public static Set<String> touchedClasses(Path execFile) throws IOException {
        ExecutionDataStore executionDataStore = new ExecutionDataStore();
        SessionInfoStore sessionInfoStore = new SessionInfoStore();
        try (InputStream in = Files.newInputStream(execFile)) {
            ExecutionDataReader reader = new ExecutionDataReader(in);
            reader.setExecutionDataVisitor(executionDataStore);
            reader.setSessionInfoVisitor(sessionInfoStore);
            reader.read();
        }
        Set<String> touched = new LinkedHashSet<>();
        for (ExecutionData data : executionDataStore.getContents()) {
            if (anyProbeHit(data)) {
                touched.add(data.getName().replace('/', '.'));
            }
        }
        return touched;
    }

    private static boolean anyProbeHit(ExecutionData data) {
        for (boolean hit : data.getProbes()) {
            if (hit) {
                return true;
            }
        }
        return false;
    }
}
