package com.automation.core.tia;

import java.util.Objects;

/**
 * One file entry from a git diff (or from the untracked-files scan), normalized to
 * repo-relative, forward-slash paths regardless of the OS git was run on.
 */
public final class ChangedFile {

    private final String path;
    private final String oldPath; // non-null only for RENAMED
    private final ChangeType type;

    public ChangedFile(String path, String oldPath, ChangeType type) {
        this.path = normalize(Objects.requireNonNull(path, "path"));
        this.oldPath = oldPath == null ? null : normalize(oldPath);
        this.type = Objects.requireNonNull(type, "type");
    }

    private static String normalize(String p) {
        return p.replace('\\', '/');
    }

    public String path() {
        return path;
    }

    public String oldPath() {
        return oldPath;
    }

    public ChangeType type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangedFile that)) return false;
        return path.equals(that.path) && Objects.equals(oldPath, that.oldPath) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, oldPath, type);
    }

    @Override
    public String toString() {
        return type + " " + (oldPath != null ? oldPath + " -> " : "") + path;
    }
}
