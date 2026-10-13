package com.automation.core.license;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Finds the license declaration of a dependency jar, offline. Order tried:
 * <ol>
 *   <li>the {@code .pom} next to the jar (Maven repository layout) - if it declares no license,
 *       the {@code <parent>} chain is followed through the same repository (a license is very often
 *       declared only on the parent POM);</li>
 *   <li>the {@code META-INF/maven/.../pom.xml} embedded in the jar;</li>
 *   <li>nothing - the caller reports the artifact as MISSING.</li>
 * </ol>
 * No network access, no execution of anything from the artifact; POMs are parsed with DOCTYPEs
 * and external entities disabled.
 */
final class PomLicenseReader {

    private static final int MAX_PARENT_DEPTH = 8;
    private static final Pattern EMBEDDED_POM = Pattern.compile("META-INF/maven/[^/]+/[^/]+/pom\\.xml");

    /** One {@code <license>} element as written. */
    record RawLicense(String name, String url) {
    }

    /** What was learned about a jar. {@code licenses} is empty when nothing was found. */
    record Result(String groupId, String artifactId, String version, List<RawLicense> licenses,
                  String source, String note) {
    }

    private record Gav(String groupId, String artifactId, String version) {
    }

    private record ParsedPom(Gav self, Gav parent, List<RawLicense> licenses) {
    }

    private PomLicenseReader() {
    }

    static Result readForJar(Path jar) {
        Path versionDir = jar.toAbsolutePath().getParent();
        Path artifactDir = versionDir == null ? null : versionDir.getParent();
        String version = versionDir == null ? "" : versionDir.getFileName().toString();
        String artifactId = artifactDir == null ? "" : artifactDir.getFileName().toString();

        Path sibling = null;
        if (versionDir != null && artifactDir != null) {
            Path candidate = versionDir.resolve(artifactId + "-" + version + ".pom");
            if (Files.isRegularFile(candidate)) {
                sibling = candidate;
            }
        }
        if (sibling != null) {
            return fromRepositoryPom(jar, sibling, artifactId, version);
        }
        return fromEmbeddedPom(jar);
    }

    private static Result fromRepositoryPom(Path jar, Path pom, String artifactId, String version) {
        ParsedPom parsed;
        try {
            parsed = parse(Files.readString(pom));
        } catch (Exception e) {
            return new Result("(unknown)", artifactId, version, List.of(), "none",
                "could not parse " + pom.getFileName() + ": " + e.getMessage());
        }
        String groupId = effectiveGroup(parsed);
        String effVersion = parsed.self().version() != null ? parsed.self().version()
            : parsed.parent() != null ? parsed.parent().version() : version;
        if (!parsed.licenses().isEmpty()) {
            return new Result(groupId, artifactId, effVersion, parsed.licenses(), "pom", "");
        }
        Path root = repositoryRoot(pom, groupId);
        ParsedPom current = parsed;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && current.parent() != null; depth++) {
            Gav parentGav = current.parent();
            Path parentPom = root == null ? null : pomPath(root, parentGav);
            if (parentPom == null || !Files.isRegularFile(parentPom)) {
                return new Result(groupId, artifactId, effVersion, List.of(), "none",
                    "no license on the POM and parent POM " + gavText(parentGav) + " is not in the local repository");
            }
            try {
                current = parse(Files.readString(parentPom));
            } catch (Exception e) {
                return new Result(groupId, artifactId, effVersion, List.of(), "none",
                    "could not parse parent POM " + gavText(parentGav) + ": " + e.getMessage());
            }
            if (!current.licenses().isEmpty()) {
                return new Result(groupId, artifactId, effVersion, current.licenses(),
                    "parent pom " + gavText(parentGav), "");
            }
        }
        // The jar itself may still carry an embedded pom with the license.
        Result embedded = fromEmbeddedPom(jar);
        if (!embedded.licenses().isEmpty()) {
            return new Result(groupId, artifactId, effVersion, embedded.licenses(), embedded.source(), "");
        }
        return new Result(groupId, artifactId, effVersion, List.of(), "none",
            "no license declared in the POM, its parents or the jar");
    }

    private static Result fromEmbeddedPom(Path jar) {
        String fallbackName = jar.getFileName().toString().replaceFirst("\\.jar$", "");
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (EMBEDDED_POM.matcher(entry.getName()).matches()) {
                    // Cap the read: a real POM is a few KB; refuse a jar entry pretending to be one.
                    try (InputStream in = jf.getInputStream(entry)) {
                        byte[] bytes = in.readNBytes(2 * 1024 * 1024);
                        ParsedPom parsed = parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                        String version = parsed.self().version() != null ? parsed.self().version()
                            : parsed.parent() != null ? parsed.parent().version() : "";
                        String artifact = parsed.self().artifactId() != null ? parsed.self().artifactId()
                            : fallbackName;
                        String note = parsed.licenses().isEmpty()
                            ? "embedded pom declares no license (parent POM not resolvable from inside a jar)" : "";
                        return new Result(effectiveGroup(parsed), artifact, version, parsed.licenses(),
                            parsed.licenses().isEmpty() ? "none" : "embedded pom", note);
                    }
                }
            }
        } catch (Exception e) {
            return new Result("(unknown)", fallbackName, "", List.of(), "none",
                "could not read jar: " + e.getMessage());
        }
        return new Result("(unknown)", fallbackName, "", List.of(), "none",
            "no .pom next to the jar and no embedded pom.xml");
    }

    private static String effectiveGroup(ParsedPom pom) {
        if (pom.self().groupId() != null) {
            return pom.self().groupId();
        }
        return pom.parent() != null ? pom.parent().groupId() : "(unknown)";
    }

    /** repository root = the directory the groupId path hangs off, found by walking up from the pom. */
    private static Path repositoryRoot(Path pom, String groupId) {
        Path dir = pom.toAbsolutePath().getParent();          // .../<version>
        for (int i = 0; i < 2 && dir != null; i++) {          // .../<artifact>, then the last group segment
            dir = dir.getParent();
        }
        int segments = groupId.split("\\.").length;
        for (int i = 0; i < segments && dir != null; i++) {
            dir = dir.getParent();
        }
        return dir;
    }

    private static Path pomPath(Path root, Gav gav) {
        if (gav.groupId() == null || gav.artifactId() == null || gav.version() == null) {
            return null;
        }
        Path p = root;
        for (String seg : gav.groupId().split("\\.")) {
            p = p.resolve(seg);
        }
        return p.resolve(gav.artifactId()).resolve(gav.version())
            .resolve(gav.artifactId() + "-" + gav.version() + ".pom");
    }

    private static String gavText(Gav gav) {
        return gav.groupId() + ":" + gav.artifactId() + ":" + gav.version();
    }

    private static ParsedPom parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // Parse problems surface as exceptions the caller handles; keep the JDK from also printing them.
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        // Resolve nothing from the network or disk, whatever the document asks for.
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        Element project = doc.getDocumentElement();
        Gav self = new Gav(childText(project, "groupId"), childText(project, "artifactId"),
            childText(project, "version"));
        Gav parent = null;
        Element parentEl = childElement(project, "parent");
        if (parentEl != null) {
            parent = new Gav(childText(parentEl, "groupId"), childText(parentEl, "artifactId"),
                childText(parentEl, "version"));
        }
        List<RawLicense> licenses = new ArrayList<>();
        Element licensesEl = childElement(project, "licenses");
        if (licensesEl != null) {
            for (Node n = licensesEl.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && "license".equals(e.getNodeName())) {
                    String name = childText(e, "name");
                    String url = childText(e, "url");
                    if ((name != null && !name.isBlank()) || (url != null && !url.isBlank())) {
                        licenses.add(new RawLicense(name == null ? "" : name, url == null ? "" : url));
                    }
                }
            }
        }
        return new ParsedPom(self, parent, licenses);
    }

    private static Element childElement(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getNodeName())) {
                return e;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element e = childElement(parent, name);
        if (e == null) {
            return null;
        }
        String text = e.getTextContent().trim().replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text;
    }
}
