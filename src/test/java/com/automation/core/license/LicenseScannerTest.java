package com.automation.core.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds a tiny fake Maven repository on disk and scans it - no network, no real dependencies needed. */
class LicenseScannerTest {

    @TempDir
    Path repo;

    private static String pom(String group, String artifact, String version, String parentXml, String licensesXml) {
        return "<project>" + parentXml
            + (group == null ? "" : "<groupId>" + group + "</groupId>")
            + "<artifactId>" + artifact + "</artifactId>"
            + (version == null ? "" : "<version>" + version + "</version>") + licensesXml
            + "</project>";
    }

    private static String licenses(String... nameUrlPairs) {
        StringBuilder sb = new StringBuilder("<licenses>");
        for (int i = 0; i < nameUrlPairs.length; i += 2) {
            sb.append("<license><name>").append(nameUrlPairs[i]).append("</name><url>")
                .append(nameUrlPairs[i + 1]).append("</url></license>");
        }
        return sb.append("</licenses>").toString();
    }

    private Path addArtifact(String group, String artifact, String version, String pomXml) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        if (pomXml != null) {
            Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), pomXml);
        }
        Path jar = dir.resolve(artifact + "-" + version + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("placeholder.txt"));
            jos.write("x".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

    private static DependencyLicense find(LicenseScanResult r, String artifactId) {
        return r.dependencies().stream().filter(d -> d.artifactId().equals(artifactId)).findFirst()
            .orElseThrow(() -> new AssertionError("no row for " + artifactId + " in " + r.dependencies()));
    }

    private static LicenseScanner scanner() {
        return new LicenseScanner(LicensePolicy.defaults());
    }

    @Test
    void licenseOnTheArtifactPom() throws Exception {
        Path jar = addArtifact("org.good", "lib-a", "1.0", pom("org.good", "lib-a", "1.0", "",
            licenses("The Apache Software License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")));
        LicenseScanResult r = scanner().scan(List.of(jar));
        DependencyLicense d = find(r, "lib-a");
        assertEquals(LicenseStatus.ALLOWED, d.status());
        assertEquals(List.of("Apache-2.0"), d.licenseIds());
        assertEquals("org.good:lib-a:1.0", d.coordinates());
        assertEquals("pom", d.source());
    }

    @Test
    void licenseInheritedFromParentPom() throws Exception {
        // groupId and version are inherited too: the child declares neither.
        Path parentDir = repo.resolve("org/parent/parent-pom/3.1");
        Files.createDirectories(parentDir);
        Files.writeString(parentDir.resolve("parent-pom-3.1.pom"),
            pom("org.parent", "parent-pom", "3.1", "", licenses("MIT License", "")));
        String child = pom(null, "child", null,
            "<parent><groupId>org.parent</groupId><artifactId>parent-pom</artifactId><version>3.1</version></parent>",
            "");
        Path jar = addArtifact("org.parent", "child", "3.1", child);

        DependencyLicense d = find(scanner().scan(List.of(jar)), "child");
        assertEquals(LicenseStatus.ALLOWED, d.status());
        assertEquals("org.parent", d.groupId());
        assertEquals("3.1", d.version());
        assertTrue(d.source().startsWith("parent pom"), d.source());
    }

    @Test
    void grandparentChainIsFollowed() throws Exception {
        Path gp = repo.resolve("org/root/root-pom/1");
        Files.createDirectories(gp);
        Files.writeString(gp.resolve("root-pom-1.pom"), pom("org.root", "root-pom", "1", "", licenses("EPL 2.0", "")));
        Path p = repo.resolve("org/mid/mid-pom/2");
        Files.createDirectories(p);
        Files.writeString(p.resolve("mid-pom-2.pom"), pom("org.mid", "mid-pom", "2",
            "<parent><groupId>org.root</groupId><artifactId>root-pom</artifactId><version>1</version></parent>", ""));
        Path jar = addArtifact("org.leaf", "leaf", "9", pom("org.leaf", "leaf", "9",
            "<parent><groupId>org.mid</groupId><artifactId>mid-pom</artifactId><version>2</version></parent>", ""));

        DependencyLicense d = find(scanner().scan(List.of(jar)), "leaf");
        assertEquals(List.of("EPL-2.0"), d.licenseIds());
    }

    @Test
    void parentMissingFromRepositoryIsReportedNotGuessed() throws Exception {
        Path jar = addArtifact("org.orphan", "orphan", "1", pom("org.orphan", "orphan", "1",
            "<parent><groupId>org.gone</groupId><artifactId>gone</artifactId><version>1</version></parent>", ""));
        DependencyLicense d = find(scanner().scan(List.of(jar)), "orphan");
        assertEquals(LicenseStatus.MISSING, d.status());
        assertTrue(d.note().contains("org.gone:gone:1"), d.note());
    }

    @Test
    void deniedAndReviewAndSorting() throws Exception {
        Path ok = addArtifact("org.a", "zzz-ok", "1", pom("org.a", "zzz-ok", "1", "", licenses("MIT", "")));
        Path gpl = addArtifact("org.b", "bad", "1", pom("org.b", "bad", "1", "",
            licenses("GNU General Public License v3.0", "")));
        Path lgpl = addArtifact("org.c", "grey", "1", pom("org.c", "grey", "1", "",
            licenses("GNU Lesser General Public License v2.1", "")));
        Path custom = addArtifact("org.d", "weird", "1", pom("org.d", "weird", "1", "",
            licenses("Acme Internal Terms", "")));

        LicenseScanResult r = scanner().scan(List.of(ok, gpl, lgpl, custom));
        assertEquals(LicenseStatus.DENIED, find(r, "bad").status());
        assertEquals(LicenseStatus.REVIEW, find(r, "grey").status());
        DependencyLicense weird = find(r, "weird");
        assertEquals(LicenseStatus.REVIEW, weird.status());
        assertEquals(List.of("Acme Internal Terms"), weird.licenseIds(), "unrecognised text is kept verbatim");

        assertEquals(LicenseStatus.DENIED, r.dependencies().get(0).status(), "worst first");
        assertEquals(LicenseStatus.ALLOWED, r.dependencies().get(r.dependencies().size() - 1).status());
        assertEquals(1, r.violations().size());
        assertEquals("bad", r.violations().get(0).artifactId());
        assertEquals(2, r.warnings().size());
    }

    @Test
    void embeddedPomIsUsedWhenThereIsNoRepositoryPom(@TempDir Path flat) throws IOException {
        Path jar = flat.resolve("standalone.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("META-INF/maven/org.emb/standalone/pom.xml"));
            jos.write(pom("org.emb", "standalone", "4.2", "", licenses("BSD 3-Clause", ""))
                .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        DependencyLicense d = find(scanner().scan(List.of(jar)), "standalone");
        assertEquals(LicenseStatus.ALLOWED, d.status());
        assertEquals("embedded pom", d.source());
        assertEquals("org.emb", d.groupId());
    }

    @Test
    void jarWithNoInformationAtAllIsMissing(@TempDir Path flat) throws IOException {
        Path jar = flat.resolve("mystery.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("a.txt"));
            jos.closeEntry();
        }
        DependencyLicense d = find(scanner().scan(List.of(jar)), "mystery");
        assertEquals(LicenseStatus.MISSING, d.status());
        assertEquals(1, new LicenseScanner(LicensePolicy.defaults()).scan(List.of(jar)).count(LicenseStatus.MISSING));
    }

    @Test
    void corruptJarAndCorruptPomDoNotCrashTheScan(@TempDir Path flat) throws IOException {
        Path notAJar = flat.resolve("broken.jar");
        Files.writeString(notAJar, "this is not a zip");
        Path jar = addArtifact("org.bad", "badpom", "1", "<project><unclosed>");
        LicenseScanResult r = scanner().scan(List.of(notAJar, jar));
        assertEquals(LicenseStatus.MISSING, find(r, "broken").status());
        assertEquals(LicenseStatus.MISSING, find(r, "badpom").status());
    }

    @Test
    void doctypeInPomIsRefusedNotExpanded() throws Exception {
        // An XXE attempt: must not be resolved (would read a local file) and must not crash the scan.
        Path secret = repo.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
            + "<project><groupId>org.x</groupId><artifactId>evil</artifactId><version>1</version>"
            + "<licenses><license><name>&xxe;</name></license></licenses></project>";
        Path jar = addArtifact("org.x", "evil", "1", evil);
        DependencyLicense d = find(scanner().scan(List.of(jar)), "evil");
        assertEquals(LicenseStatus.MISSING, d.status());
        assertTrue(d.licenseIds().stream().noneMatch(i -> i.contains("TOP-SECRET")), d.licenseIds().toString());
        assertTrue(d.declared().stream().noneMatch(i -> i.contains("TOP-SECRET")));
    }

    @Test
    void sameArtifactAsTwoJarsIsOneRow() throws Exception {
        Path main = addArtifact("org.dup", "dup", "1", pom("org.dup", "dup", "1", "", licenses("MIT", "")));
        Path tests = main.resolveSibling("dup-1-tests.jar");
        Files.copy(main, tests);
        LicenseScanResult r = scanner().scan(List.of(main, tests));
        assertEquals(1, r.dependencies().size());
    }

    @Test
    void ignoreAndOverrideFromPolicy() throws Exception {
        Properties props = new Properties();
        props.setProperty("license.ignore", "org.own:*");
        props.setProperty("license.overrides", "org.fix:mislabelled=Apache-2.0");
        LicensePolicy policy = LicensePolicy.fromProperties(props, k -> null);

        Path own = addArtifact("org.own", "internal", "1", pom("org.own", "internal", "1", "",
            licenses("Proprietary", "")));
        Path fix = addArtifact("org.fix", "mislabelled", "1", pom("org.fix", "mislabelled", "1", "",
            licenses("Weird Words", "")));
        LicenseScanResult r = new LicenseScanner(policy).scan(List.of(own, fix));

        assertEquals(1, r.ignored());
        assertEquals(1, r.dependencies().size());
        DependencyLicense d = r.dependencies().get(0);
        assertEquals(LicenseStatus.ALLOWED, d.status());
        assertEquals("policy override", d.source());
    }
}
