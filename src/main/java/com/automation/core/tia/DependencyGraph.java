package com.automation.core.tia;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Forward dependency graph over project classes (top-level granularity): class X -&gt; the set
 * of other project classes whose fully-qualified name appears anywhere in X's compiled constant
 * pool (see {@link ClassFileScanner}). {@link #reverseTransitiveClosure} answers the actual TIA
 * question by walking that graph backwards: "given these classes changed, which classes
 * (directly or transitively) depend on them, and therefore might behave differently now".
 */
public final class DependencyGraph {

    private final Map<String, Set<String>> forward = new TreeMap<>();

    public static DependencyGraph build(Map<String, Set<String>> utf8ByClass) {
        DependencyGraph graph = new DependencyGraph();
        Set<String> allClasses = utf8ByClass.keySet();
        for (Map.Entry<String, Set<String>> entry : utf8ByClass.entrySet()) {
            String owner = entry.getKey();
            Set<String> deps = new LinkedHashSet<>();
            for (String candidate : allClasses) {
                if (candidate.equals(owner)) {
                    continue;
                }
                if (referencesClass(entry.getValue(), candidate)) {
                    deps.add(candidate);
                }
            }
            graph.forward.put(owner, deps);
        }
        return graph;
    }

    private static boolean referencesClass(Set<String> utf8Strings, String candidateFqcn) {
        String slashForm = candidateFqcn.replace('.', '/');
        for (String s : utf8Strings) {
            if (s.equals(candidateFqcn) || s.equals(slashForm)) {
                return true;
            }
            // Descriptors/signatures embed classes as "Lcom/automation/.../Foo;" (and array/generic
            // variants around that same substring); reflective literals use the dotted form.
            if (s.contains(slashForm) || s.contains(candidateFqcn)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> directDependencies(String fqcn) {
        return forward.getOrDefault(fqcn, Set.of());
    }

    public Set<String> knownClasses() {
        return forward.keySet();
    }

    /**
     * BFS over the reversed graph: every class reachable by following "depends on" edges
     * backward from {@code changed} — i.e. every class that (directly or transitively) depends
     * on something in {@code changed}. The result always includes the seed classes themselves.
     */
    public Set<String> reverseTransitiveClosure(Set<String> changed) {
        Map<String, Set<String>> reverse = reversed();
        Set<String> visited = new LinkedHashSet<>(changed);
        Deque<String> queue = new ArrayDeque<>(changed);
        while (!queue.isEmpty()) {
            String next = queue.poll();
            for (String dependent : reverse.getOrDefault(next, Set.of())) {
                if (visited.add(dependent)) {
                    queue.add(dependent);
                }
            }
        }
        return visited;
    }

    private Map<String, Set<String>> reversed() {
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : forward.entrySet()) {
            for (String dep : e.getValue()) {
                reverse.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        return reverse;
    }
}
