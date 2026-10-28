package com.automation.core.tia;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitDiffReaderTest {

    @Test
    void parsesModifiedAndAddedAndDeletedLines() {
        List<ChangedFile> files = GitDiffReader.parseNameStatus(List.of(
            "M\tsrc/main/java/com/automation/core/util/StringHelper.java",
            "A\tsrc/test/java/com/automation/sites/demoqa/tests/NewFeatureTest.java",
            "D\tsrc/test/java/com/automation/sites/demoqa/tests/OldTest.java"
        ));
        assertEquals(3, files.size());
        assertEquals(ChangeType.MODIFIED, files.get(0).type());
        assertEquals("src/main/java/com/automation/core/util/StringHelper.java", files.get(0).path());
        assertEquals(ChangeType.ADDED, files.get(1).type());
        assertEquals(ChangeType.DELETED, files.get(2).type());
    }

    @Test
    void parsesRenameWithOldAndNewPath() {
        List<ChangedFile> files = GitDiffReader.parseNameStatus(List.of(
            "R100\tsrc/test/java/com/automation/sites/demoqa/tests/Old.java"
                + "\tsrc/test/java/com/automation/sites/demoqa/tests/New.java"
        ));
        assertEquals(1, files.size());
        ChangedFile f = files.get(0);
        assertEquals(ChangeType.RENAMED, f.type());
        assertEquals("src/test/java/com/automation/sites/demoqa/tests/Old.java", f.oldPath());
        assertEquals("src/test/java/com/automation/sites/demoqa/tests/New.java", f.path());
    }

    @Test
    void ignoresBlankLines() {
        List<ChangedFile> files = GitDiffReader.parseNameStatus(List.of("", "M\tpom.xml", ""));
        assertEquals(1, files.size());
    }

    @Test
    void treatsCopyAndTypechangeAsModified() {
        List<ChangedFile> files = GitDiffReader.parseNameStatus(List.of(
            "C100\tsrc/main/java/com/automation/core/util/StringHelper.java\tsrc/main/java/com/automation/core/util/StringHelper2.java",
            "T\tScripts/new-site.sh"
        ));
        assertEquals(ChangeType.MODIFIED, files.get(0).type());
        assertEquals(ChangeType.MODIFIED, files.get(1).type());
    }

    @Test
    void acceptsNormalRevisions() {
        for (String ref : List.of("HEAD", "HEAD~1", "origin/main", "main^", "v1.2.3", "feature/login-fix",
            "3f2a9c1", "HEAD@{1}", "refs/heads/main")) {
            assertDoesNotThrow(() -> GitDiffReader.requireSafeRef("base", ref), ref);
        }
        assertDoesNotThrow(() -> GitDiffReader.requireSafeRef("head", null));
        assertDoesNotThrow(() -> GitDiffReader.requireSafeRef("head", "  "));
    }

    @Test
    void rejectsValuesGitCouldReadAsOptionsOrThatCarryOddCharacters() {
        for (String ref : List.of("--output=/tmp/x", "-p", "main; rm -rf /", "a b", "main\n--stat", "$(id)", "a|b", "x`y`")) {
            assertThrows(IllegalArgumentException.class, () -> GitDiffReader.requireSafeRef("base", ref), ref);
        }
    }
}
