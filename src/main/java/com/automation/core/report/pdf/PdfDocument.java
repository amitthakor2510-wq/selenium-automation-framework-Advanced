package com.automation.core.report.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A tiny, dependency-free PDF 1.4 writer - just enough for the test report: A4 pages, the three
 * standard fonts (no embedding needed), filled/stroked rectangles, lines, JPEG images and bookmarks.
 *
 * <p>Why not a PDF library: the framework already ships a license scanner, a Docker image and an
 * offline CI story; a writer this small adds no jar, no license entry and nothing to keep patched.
 *
 * <p>Text uses WinAnsi (Windows-1252) encoding, so Latin text, curly quotes, dashes and the euro sign
 * render correctly; any character outside that set is written as {@code ?} rather than corrupting
 * the file. Package-private on purpose - {@link PdfReportWriter} is the public entry point.
 */
final class PdfDocument {

    static final float PAGE_WIDTH = 595.28f;
    static final float PAGE_HEIGHT = 841.89f;

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /** Standard-14 fonts. Widths are the Adobe AFM advance widths (1/1000 em) for ASCII 32..126. */
    enum Font {
        REGULAR("Helvetica", "F1", new int[] {
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584}),
        BOLD("Helvetica-Bold", "F2", new int[] {
            278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
            975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
            333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
            611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584}),
        MONO("Courier", "F3", null);

        final String baseName;
        final String resourceName;
        private final int[] widths;

        Font(String baseName, String resourceName, int[] widths) {
            this.baseName = baseName;
            this.resourceName = resourceName;
            this.widths = widths;
        }

        /** Advance width of one WinAnsi byte, in 1/1000 em. */
        int width(int winAnsiByte) {
            int b = winAnsiByte & 0xFF;
            if (widths == null) {
                return 600;
            }
            if (b >= 32 && b <= 126) {
                return widths[b - 32];
            }
            boolean bold = this == BOLD;
            return switch (b) {
                case 0x85, 0x97 -> 1000;     // ellipsis, em dash
                case 0x95 -> 350;            // bullet
                case 0x91, 0x92 -> bold ? 278 : 222;
                case 0x93, 0x94 -> bold ? 500 : 333;
                case 0xA0 -> 278;            // no-break space
                default -> 556;
            };
        }
    }

    /** One page: a PDF content stream plus the images it references. */
    static final class Page {
        private final StringBuilder content = new StringBuilder(4096);
        private final Set<Integer> images = new LinkedHashSet<>();

        void fillRect(float x, float y, float w, float h, int rgb) {
            content.append("q ").append(color(rgb)).append(" rg ")
                .append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h))
                .append(" re f Q\n");
        }

        /** Fills a closed polygon given as x0,y0,x1,y1,... */
        void polygon(float[] points, int rgb) {
            if (points.length < 6) {
                return;
            }
            content.append("q ").append(color(rgb)).append(" rg ").append(n(points[0])).append(' ')
                .append(n(points[1])).append(" m ");
            for (int i = 2; i + 1 < points.length; i += 2) {
                content.append(n(points[i])).append(' ').append(n(points[i + 1])).append(" l ");
            }
            content.append("h f Q\n");
        }

        void strokeRect(float x, float y, float w, float h, int rgb, float lineWidth) {
            content.append("q ").append(color(rgb)).append(" RG ").append(n(lineWidth)).append(" w ")
                .append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h))
                .append(" re S Q\n");
        }

        void line(float x1, float y1, float x2, float y2, int rgb, float lineWidth) {
            content.append("q ").append(color(rgb)).append(" RG ").append(n(lineWidth)).append(" w ")
                .append(n(x1)).append(' ').append(n(y1)).append(" m ")
                .append(n(x2)).append(' ').append(n(y2)).append(" l S Q\n");
        }

        void text(Font font, float size, float x, float y, String text, int rgb) {
            if (text == null || text.isEmpty()) {
                return;
            }
            content.append("BT /").append(font.resourceName).append(' ').append(n(size)).append(" Tf ")
                .append(color(rgb)).append(" rg ").append(n(x)).append(' ').append(n(y)).append(" Td <");
            for (byte b : encode(text)) {
                content.append(String.format("%02X", b & 0xFF));
            }
            content.append("> Tj ET\n");
        }

        void image(int imageId, float x, float y, float w, float h) {
            images.add(imageId);
            content.append("q ").append(n(w)).append(" 0 0 ").append(n(h)).append(' ').append(n(x)).append(' ')
                .append(n(y)).append(" cm /Im").append(imageId).append(" Do Q\n");
        }
    }

    private record ImageData(byte[] jpeg, int width, int height) {
    }

    private record Bookmark(String title, int pageIndex, float y) {
    }

    private final List<Page> pages = new ArrayList<>();
    private final List<ImageData> images = new ArrayList<>();
    private final List<Bookmark> bookmarks = new ArrayList<>();

    Page newPage() {
        Page page = new Page();
        pages.add(page);
        return page;
    }

    int pageCount() {
        return pages.size();
    }

    Page page(int index) {
        return pages.get(index);
    }

    /** Registers a baseline RGB JPEG; the returned id is what {@link Page#image} takes. */
    int addJpeg(byte[] jpeg, int pixelWidth, int pixelHeight) {
        images.add(new ImageData(jpeg, pixelWidth, pixelHeight));
        return images.size();
    }

    void addBookmark(String title, int pageIndex, float y) {
        bookmarks.add(new Bookmark(title, pageIndex, y));
    }

    // ---- text helpers ------------------------------------------------------------------------

    /** Maps text to WinAnsi bytes; anything unrepresentable (or a control character) becomes a safe stand-in. */
    static byte[] encode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t') {
                out.write(' ');
            } else if (cp < 32 || cp == 127) {
                out.write(' ');
            } else if (cp < 128) {
                out.write(cp);
            } else {
                byte[] mapped = new String(Character.toChars(cp)).getBytes(WIN_ANSI);
                // Windows-1252 turns an unmappable character into '?', which is what we want.
                out.write(mapped.length == 1 ? mapped[0] : '?');
            }
        }
        return out.toByteArray();
    }

    static float width(Font font, float size, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (byte b : encode(text)) {
            total += font.width(b);
        }
        return total * size / 1000f;
    }

    // ---- serialisation -----------------------------------------------------------------------

    /** Serialises the whole document. May be called once the last page has been drawn. */
    byte[] toBytes(String title, String author, String subject, Instant created) {
        if (pages.isEmpty()) {
            newPage();
        }
        final int catalogId = 1;
        final int pagesId = 2;
        final int infoId = 3;
        final int firstFontId = 4;
        final int fontCount = Font.values().length;
        final int firstImageId = firstFontId + fontCount;
        final int firstPageId = firstImageId + images.size();
        final int firstOutlineId = firstPageId + pages.size() * 2;
        final boolean hasOutline = !bookmarks.isEmpty();
        final int outlineRootId = firstOutlineId;
        final int total = hasOutline ? firstOutlineId + bookmarks.size() : firstOutlineId - 1;

        long[] offsets = new long[total + 1];
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

            offsets[catalogId] = begin(out, catalogId);
            String catalog = "<< /Type /Catalog /Pages " + pagesId + " 0 R"
                + (hasOutline ? " /Outlines " + outlineRootId + " 0 R" : "") + " >>\n";
            end(out, catalog);

            offsets[pagesId] = begin(out, pagesId);
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                kids.append(firstPageId + i * 2).append(" 0 R ");
            }
            end(out, "<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>\n");

            offsets[infoId] = begin(out, infoId);
            String date = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
                .withZone(ZoneOffset.UTC).format(created == null ? Instant.now() : created);
            end(out, "<< /Title " + hex(title) + " /Author " + hex(author) + " /Subject " + hex(subject)
                + " /Creator " + hex("Selenium Automation Framework") + " /Producer "
                + hex("Selenium Automation Framework PDF report") + " /CreationDate (D:" + date + "Z) >>\n");

            for (Font font : Font.values()) {
                int id = firstFontId + font.ordinal();
                offsets[id] = begin(out, id);
                end(out, "<< /Type /Font /Subtype /Type1 /BaseFont /" + font.baseName
                    + " /Encoding /WinAnsiEncoding >>\n");
            }

            for (int i = 0; i < images.size(); i++) {
                ImageData img = images.get(i);
                int id = firstImageId + i;
                offsets[id] = begin(out, id);
                out.write(("<< /Type /XObject /Subtype /Image /Width " + img.width() + " /Height " + img.height()
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + img.jpeg().length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
                out.write(img.jpeg());
                out.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            }

            for (int i = 0; i < pages.size(); i++) {
                Page page = pages.get(i);
                int pageId = firstPageId + i * 2;
                int contentId = pageId + 1;

                StringBuilder fonts = new StringBuilder();
                for (Font font : Font.values()) {
                    fonts.append('/').append(font.resourceName).append(' ')
                        .append(firstFontId + font.ordinal()).append(" 0 R ");
                }
                StringBuilder xobjects = new StringBuilder();
                for (int imageId : page.images) {
                    xobjects.append("/Im").append(imageId).append(' ')
                        .append(firstImageId + imageId - 1).append(" 0 R ");
                }
                offsets[pageId] = begin(out, pageId);
                end(out, "<< /Type /Page /Parent " + pagesId + " 0 R /MediaBox [0 0 " + n(PAGE_WIDTH) + ' '
                    + n(PAGE_HEIGHT) + "] /Resources << /Font << " + fonts + ">>"
                    + (xobjects.length() > 0 ? " /XObject << " + xobjects + ">>" : "")
                    + " >> /Contents " + contentId + " 0 R >>\n");

                byte[] stream = deflate(page.content.toString().getBytes(StandardCharsets.ISO_8859_1));
                offsets[contentId] = begin(out, contentId);
                out.write(("<< /Length " + stream.length + " /Filter /FlateDecode >>\nstream\n")
                    .getBytes(StandardCharsets.US_ASCII));
                out.write(stream);
                out.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            }

            if (hasOutline) {
                offsets[outlineRootId] = begin(out, outlineRootId);
                end(out, "<< /Type /Outlines /First " + (outlineRootId + 1) + " 0 R /Last "
                    + (outlineRootId + bookmarks.size()) + " 0 R /Count " + bookmarks.size() + " >>\n");
                for (int i = 0; i < bookmarks.size(); i++) {
                    Bookmark b = bookmarks.get(i);
                    int id = outlineRootId + 1 + i;
                    int pageId = firstPageId + Math.min(b.pageIndex(), pages.size() - 1) * 2;
                    offsets[id] = begin(out, id);
                    end(out, "<< /Title " + hex(b.title()) + " /Parent " + outlineRootId + " 0 R"
                        + (i > 0 ? " /Prev " + (id - 1) + " 0 R" : "")
                        + (i < bookmarks.size() - 1 ? " /Next " + (id + 1) + " 0 R" : "")
                        + " /Dest [" + pageId + " 0 R /XYZ 0 " + n(b.y()) + " 0] >>\n");
                }
            }

            long xref = out.size();
            StringBuilder table = new StringBuilder();
            table.append("xref\n0 ").append(total + 1).append("\n0000000000 65535 f \n");
            for (int id = 1; id <= total; id++) {
                table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id]));
            }
            table.append("trailer\n<< /Size ").append(total + 1).append(" /Root ").append(catalogId)
                .append(" 0 R /Info ").append(infoId).append(" 0 R >>\nstartxref\n").append(xref)
                .append("\n%%EOF\n");
            out.write(table.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static long begin(ByteArrayOutputStream out, int id) throws IOException {
        long offset = out.size();
        out.write((id + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
        return offset;
    }

    private static void end(ByteArrayOutputStream out, String body) throws IOException {
        out.write(body.getBytes(StandardCharsets.US_ASCII));
        out.write("endobj\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 16);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, deflater)) {
            dos.write(data);
        } finally {
            deflater.end();
        }
        return bos.toByteArray();
    }

    /** A PDF text string as UTF-16BE hex with a byte-order mark, so any title survives intact. */
    private static String hex(String text) {
        StringBuilder sb = new StringBuilder("<FEFF");
        for (byte b : (text == null ? "" : text).getBytes(StandardCharsets.UTF_16BE)) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.append('>').toString();
    }

    private static String color(int rgb) {
        return n(((rgb >> 16) & 0xFF) / 255f) + ' ' + n(((rgb >> 8) & 0xFF) / 255f) + ' ' + n((rgb & 0xFF) / 255f);
    }

    /** Locale-independent number formatting (a decimal comma would corrupt the content stream). */
    private static String n(float value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }
}
