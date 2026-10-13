package com.automation.core.license;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps the free-text license names and URLs found in POMs ("The Apache Software License,
 * Version 2.0", "Eclipse Public License - v 2.0", "CDDL + GPLv2 with classpath exception", ...)
 * to short SPDX-style identifiers so a policy can be written once, against stable names.
 *
 * <p>A text that names two licenses (a dual license) yields both identifiers. Text that matches
 * nothing yields an empty list - the caller then keeps the raw text, so an unrecognised license
 * is reported for review instead of silently passing.
 */
public final class LicenseNormalizer {

    private LicenseNormalizer() {
    }

    /** Returns the identifiers for one declared license (name and/or URL); empty when unrecognised. */
    public static List<String> normalize(String name, String url) {
        List<String> ids = fromText(name);
        if (ids.isEmpty()) {
            ids = fromUrl(url);
        }
        return ids;
    }

    private static List<String> fromText(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        // Lower-case, punctuation -> single spaces, padded so " word " tests are word-bounded.
        String t = " " + raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", " ").replace(".", " ")
            .replaceAll("\\bv(?=\\d)", "v ").replaceAll("\\s+", " ").trim() + " ";
        // SPDX identifiers written verbatim ("Apache-2.0", "BSD-3-Clause") reduce to the same words.

        boolean classpathException = t.contains(" classpath exception ");

        if (t.contains(" cddl ") || t.contains(" common development and distribution ")) {
            out.add(t.contains(" 1 0 ") && !t.contains(" 1 1 ") ? "CDDL-1.0" : "CDDL-1.1");
        }
        if (t.contains(" lesser general public ") || t.contains(" lgpl ") || t.contains(" lgplv2 ")
            || t.contains(" lgplv3 ") || t.contains(" lgpl2 1 ")) {
            out.add(t.contains(" 3 ") || t.contains(" 3 0 ") || t.contains("lgplv3") ? "LGPL-3.0" : "LGPL-2.1");
        } else if (t.contains(" affero ") || t.contains(" agpl ")) {
            out.add("AGPL-3.0");
        } else if (t.contains(" general public ") || t.contains(" gpl ") || t.contains(" gplv2 ")
            || t.contains(" gplv3 ") || t.contains(" gpl2 ") || t.contains(" gpl3 ")) {
            String base = t.contains(" 3 ") || t.contains(" 3 0 ") || t.contains(" gplv3 ")
                || t.contains(" gpl3 ") ? "GPL-3.0" : "GPL-2.0";
            out.add(classpathException ? base + "-with-classpath-exception" : base);
        }
        if (t.contains(" apache ")) {
            out.add(t.contains(" 1 1 ") ? "Apache-1.1" : t.contains(" 1 0 ") ? "Apache-1.0" : "Apache-2.0");
        }
        if (t.contains(" eclipse distribution ") || t.contains(" edl ")) {
            out.add("BSD-3-Clause");
        } else if (t.contains(" eclipse public ") || t.contains(" epl ")) {
            out.add(t.contains(" 2 0 ") || t.contains(" epl2 ") ? "EPL-2.0" : "EPL-1.0");
        }
        if (t.contains(" mozilla public ") || t.contains(" mpl ")) {
            out.add(t.contains(" 1 1 ") ? "MPL-1.1" : "MPL-2.0");
        }
        if (t.contains(" bsd ") || t.contains(" bsd3 ") || t.contains(" bsd2 ") || t.contains(" 3 clause ")
            || t.contains(" 2 clause ") || t.contains(" 4 clause ")) {
            if (t.contains(" 4 clause ")) {
                out.add("BSD-4-Clause");
            } else if (t.contains(" 2 clause ") || t.contains(" simplified ") || t.contains(" freebsd ")
                || t.contains(" bsd2 ")) {
                out.add("BSD-2-Clause");
            } else {
                out.add("BSD-3-Clause");
            }
        }
        if (t.contains(" mit ") || t.contains(" expat ") || t.contains(" bouncy castle ")) {
            out.add("MIT");
        }
        if (t.contains(" isc ")) {
            out.add("ISC");
        }
        if (t.contains(" cc0 ") || t.contains(" creative commons zero ")) {
            out.add("CC0-1.0");
        }
        if (t.contains(" public domain ")) {
            out.add("Public-Domain");
        }
        if (t.contains(" unlicense ")) {
            out.add("Unlicense");
        }
        if (t.contains(" zlib ")) {
            out.add("Zlib");
        }
        if (t.contains(" sspl ") || t.contains(" server side public ")) {
            out.add("SSPL-1.0");
        }
        if (t.contains(" business source ") || t.contains(" busl ")) {
            out.add("BUSL-1.1");
        }
        if (t.contains(" commons clause ")) {
            out.add("Commons-Clause");
        }
        if (t.equals(" json license ") || t.equals(" the json license ")) {
            out.add("JSON");
        }
        return out.stream().distinct().toList();
    }

    private static List<String> fromUrl(String rawUrl) {
        List<String> out = new ArrayList<>();
        if (rawUrl == null || rawUrl.isBlank()) {
            return out;
        }
        String u = rawUrl.toLowerCase(Locale.ROOT);
        if (u.contains("apache.org/licenses/license-2.0")) {
            out.add("Apache-2.0");
        } else if (u.contains("opensource.org/licenses/mit") || u.contains("opensource.org/license/mit")) {
            out.add("MIT");
        } else if (u.contains("opensource.org/licenses/bsd-3") || u.contains("opensource.org/license/bsd-3")) {
            out.add("BSD-3-Clause");
        } else if (u.contains("opensource.org/licenses/bsd-2") || u.contains("opensource.org/license/bsd-2")) {
            out.add("BSD-2-Clause");
        } else if (u.contains("eclipse.org/legal/epl-2.0") || u.contains("eclipse.org/legal/epl-v20")) {
            out.add("EPL-2.0");
        } else if (u.contains("eclipse.org/legal/epl-v10") || u.contains("eclipse.org/legal/epl-1.0")) {
            out.add("EPL-1.0");
        } else if (u.contains("eclipse.org/org/documents/edl-v10")) {
            out.add("BSD-3-Clause");
        } else if (u.contains("mozilla.org/mpl/2.0") || u.contains("mozilla.org/en-us/mpl/2.0")) {
            out.add("MPL-2.0");
        } else if (u.contains("gnu.org/licenses/lgpl-3.0")) {
            out.add("LGPL-3.0");
        } else if (u.contains("gnu.org/licenses/lgpl") || u.contains("gnu.org/copyleft/lesser")) {
            out.add("LGPL-2.1");
        } else if (u.contains("gnu.org/licenses/agpl")) {
            out.add("AGPL-3.0");
        } else if (u.contains("gnu.org/licenses/gpl-3.0")) {
            out.add("GPL-3.0");
        } else if (u.contains("gnu.org/licenses/gpl-2.0") || u.contains("gnu.org/copyleft/gpl")) {
            out.add("GPL-2.0");
        } else if (u.contains("creativecommons.org/publicdomain/zero")) {
            out.add("CC0-1.0");
        }
        return out;
    }
}
