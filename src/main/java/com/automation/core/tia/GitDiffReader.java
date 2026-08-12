package com.automation.core.tia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps {@code git diff --name-status} (plus {@code git ls-files --others} for brand-new,
 * not-yet-tracked files, which {@code git diff} never reports) to produce the raw list of
 * {@link ChangedFile} the rest of TIA classifies and maps back to tests.
 */
public final class GitDiffReader {

    private final Path repoRoot;

    public GitDiffReader(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /**
     * Diffs {@code base} against {@code head}. If {@code head} is null/blank, diffs base
     * against the current working tree — i.e. "everything I've changed so far, committed
     * or not" — which is the useful mode for a developer iterating locally. In CI, pass
     * both (e.g. {@code origin/main} and the PR's merge commit) for a fixed comparison.
     */
    public List<ChangedFile> diff(String base, String head) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("diff");
        cmd.add("--no-color");
        cmd.add("--find-renames");
        cmd.add("--name-status");
        if (base != null && !base.isBlank()) {
            if (head != null && !head.isBlank()) {
                cmd.add(base + "..." + head);
            } else {
                cmd.add(base);
            }
        }
        return parseNameStatus(run(cmd));
    }

    /** Files that are new and not yet tracked/committed at all — {@code git diff} never reports these. */
    public List<ChangedFile> untracked() throws IOException, InterruptedException {
        List<String> lines = run(List.of("git", "ls-files", "--others", "--exclude-standard"));
        List<ChangedFile> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                out.add(new ChangedFile(line.trim(), null, ChangeType.ADDED));
            }
        }
        return out;
    }

    static List<ChangedFile> parseNameStatus(List<String> lines) {
        List<ChangedFile> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            ChangeType type = classify(parts[0]);
            if (type == ChangeType.RENAMED && parts.length >= 3) {
                out.add(new ChangedFile(parts[2], parts[1], type));
            } else {
                out.add(new ChangedFile(parts[1], null, type));
            }
        }
        return out;
    }

    private static ChangeType classify(String status) {
        char c = status.isEmpty() ? '?' : status.charAt(0);
        return switch (c) {
            case 'A' -> ChangeType.ADDED;
            case 'D' -> ChangeType.DELETED;
            case 'R' -> ChangeType.RENAMED;
            // M(odified), C(opied), T(ypechange), U(nmerged) — all handled the same,
            // conservative way: treat as a modification of an existing file.
            default -> ChangeType.MODIFIED;
        };
    }

    private List<String> run(List<String> cmd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(cmd)
            .directory(repoRoot.toFile())
            .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out after 60s: " + cmd);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git command failed (exit " + process.exitValue() + "): " + cmd
                + (stderr.isBlank() ? "" : "\n" + stderr));
        }
        return lines;
    }
}
