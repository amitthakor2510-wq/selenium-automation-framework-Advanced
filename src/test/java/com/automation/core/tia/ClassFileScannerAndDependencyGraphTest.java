package com.automation.core.tia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ClassFileScanner} and {@link DependencyGraph} against real {@code javac}
 * output (see {@link TiaTestFixtures#compile}) rather than hand-built constant pools — the class
 * file format itself is what {@link ClassFileScanner} has to parse correctly.
 */
class ClassFileScannerAndDependencyGraphTest {

    @Test
    void methodCallDependencyIsDiscovered(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        TiaTestFixtures.write(src, "a/Util.java", "package a;\npublic class Util { public static int add(int x, int y) { return x + y; } }\n");
        TiaTestFixtures.write(src, "a/Caller.java", "package a;\npublic class Caller { void go() { Util.add(1, 2); } }\n");
        TiaTestFixtures.compile(src, out);

        Map<String, Set<String>> utf8ByClass = ClassFileScanner.scan(List.of(out));
        DependencyGraph graph = DependencyGraph.build(utf8ByClass);

        assertTrue(graph.directDependencies("a.Caller").contains("a.Util"));
        assertFalse(graph.directDependencies("a.Util").contains("a.Caller"));
    }

    @Test
    void inheritanceDependencyIsDiscovered(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        TiaTestFixtures.write(src, "a/Base.java", "package a;\npublic abstract class Base { void setUp() {} }\n");
        TiaTestFixtures.write(src, "a/Sub.java", "package a;\npublic class Sub extends Base { void run() {} }\n");
        TiaTestFixtures.compile(src, out);

        DependencyGraph graph = DependencyGraph.build(ClassFileScanner.scan(List.of(out)));

        assertTrue(graph.directDependencies("a.Sub").contains("a.Base"));
    }

    @Test
    void reverseTransitiveClosureFollowsChainOfDependents(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        TiaTestFixtures.write(src, "a/Leaf.java", "package a;\npublic class Leaf { static void f() {} }\n");
        TiaTestFixtures.write(src, "a/Mid.java", "package a;\npublic class Mid { void g() { Leaf.f(); } }\n");
        TiaTestFixtures.write(src, "a/Top.java", "package a;\npublic class Top { void h(Mid m) { m.g(); } }\n");
        TiaTestFixtures.write(src, "a/Unrelated.java", "package a;\npublic class Unrelated { }\n");
        TiaTestFixtures.compile(src, out);

        DependencyGraph graph = DependencyGraph.build(ClassFileScanner.scan(List.of(out)));
        Set<String> closure = graph.reverseTransitiveClosure(Set.of("a.Leaf"));

        assertEquals(Set.of("a.Leaf", "a.Mid", "a.Top"), closure);
    }

    @Test
    void nestedAndAnonymousClassesRollUpIntoTheirTopLevelOwner(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        TiaTestFixtures.write(src, "a/Dep.java", "package a;\npublic class Dep { static void f() {} }\n");
        TiaTestFixtures.write(src, "a/HasInner.java",
            "package a;\npublic class HasInner {\n"
                + "  class Inner { void call() { Dep.f(); } }\n"
                + "  Runnable r = new Runnable() { public void run() { } };\n"
                + "}\n");
        TiaTestFixtures.compile(src, out);

        Map<String, Set<String>> byTopLevel = ClassFileScanner.scan(List.of(out));

        // Only top-level names appear as keys — no "HasInner$Inner" / "HasInner$1".
        assertTrue(byTopLevel.containsKey("a.HasInner"));
        assertFalse(byTopLevel.containsKey("a.HasInner$Inner"));

        DependencyGraph graph = DependencyGraph.build(byTopLevel);
        assertTrue(graph.directDependencies("a.HasInner").contains("a.Dep"));
    }
}
