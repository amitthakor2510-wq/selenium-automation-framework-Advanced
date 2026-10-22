package com.automation.core.mail;

import com.automation.core.license.LicenseReportWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Chooses the files to attach: the PDF report(s), the report ZIP when it exists, otherwise each Extent {@code index.html}
 * (self-contained - the framework enables Extent offline mode), plus the license report. Only files
 * modified since the run started are considered, so a stale report from an earlier run is never mailed
 * as if it were this one. Anything over the total size cap is left out and reported in {@link #notes()}.
 */
public final class EmailAttachments {

    /** One file to attach, under the name the recipient will see. */
    public record Item(Path file, String name) {
    }

    private final List<Item> items = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public List<Item> items() {
        return items;
    }

    public List<String> notes() {
        return notes;
    }

    public static EmailAttachments collect(EmailSettings settings, Instant notBefore) {
        EmailAttachments result = new EmailAttachments();
        long budget = settings.maxAttachmentBytes();

        // PDFs first: they are the readable summary and small, so they get the size budget before the zip.
        if (settings.attachPdf()) {
            budget = addPdfs(settings, notBefore, result, budget);
        }
        if (settings.attachReports()) {
            budget = addReports(settings, notBefore, result, budget);
        }
        if (settings.attachLicenseReport()) {
            Path html = settings.licenseDir().resolve(LicenseReportWriter.HTML);
            if (isFresh(html, notBefore)) {
                addIfFits(result, html, "license-report.html", budget);
            }
        }
        return result;
    }

    /** Every PDF in {@code report.pdf.output.dir} written since the run started (stale ones are never sent). */
    private static long addPdfs(EmailSettings settings, Instant notBefore, EmailAttachments result, long budget) {
        long remaining = budget;
        Path dir = settings.pdfDir();
        if (!Files.isDirectory(dir)) {
            return remaining;
        }
        List<Path> pdfs = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
                .filter(p -> isFresh(p, notBefore)).sorted().forEach(pdfs::add);
        } catch (IOException e) {
            result.notes.add("Could not list " + dir + ": " + e.getMessage());
            return remaining;
        }
        for (Path pdf : pdfs) {
            long size = sizeOf(pdf);
            if (size <= remaining) {
                result.items.add(new Item(pdf, pdf.getFileName().toString()));
                remaining -= size;
            } else {
                result.notes.add("PDF report " + pdf.getFileName() + " was not attached (" + mb(size)
                    + " MB would exceed the " + mb(budget) + " MB limit).");
            }
        }
        return remaining;
    }

    private static long addReports(EmailSettings settings, Instant notBefore, EmailAttachments result, long budget) {
        long remaining = budget;
        Path zip = settings.zipFile();
        if (isFresh(zip, notBefore)) {
            long size = sizeOf(zip);
            if (size <= remaining) {
                result.items.add(new Item(zip, zip.getFileName().toString()));
                return remaining - size;
            }
            result.notes.add("Report archive " + zip.getFileName() + " was not attached (" + mb(size)
                + " MB exceeds the " + mb(budget) + " MB limit).");
            return remaining;
        }
        Path dir = settings.extentDir();
        if (!Files.isDirectory(dir)) {
            return remaining;
        }
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.getFileName().toString().equals("index.html")).filter(Files::isRegularFile)
                .filter(p -> isFresh(p, notBefore)).sorted().forEach(reports::add);
        } catch (IOException e) {
            result.notes.add("Could not list " + dir + ": " + e.getMessage());
            return remaining;
        }
        for (Path report : reports) {
            String name = dir.relativize(report).toString().replace('\\', '/').replace('/', '-');
            long size = sizeOf(report);
            if (size <= remaining) {
                result.items.add(new Item(report, name));
                remaining -= size;
            } else {
                result.notes.add("Report " + name + " was not attached (" + mb(size)
                    + " MB would exceed the " + mb(budget) + " MB limit).");
            }
        }
        return remaining;
    }

    private static void addIfFits(EmailAttachments result, Path file, String name, long budget) {
        long used = result.items.stream().mapToLong(i -> sizeOf(i.file())).sum();
        long size = sizeOf(file);
        if (used + size <= budget) {
            result.items.add(new Item(file, name));
        } else {
            result.notes.add(name + " was not attached (size limit).");
        }
    }

    private static boolean isFresh(Path file, Instant notBefore) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            // 2 s of slack: some file systems store whole-second modification times.
            return notBefore == null
                || !Files.getLastModifiedTime(file).toInstant().isBefore(notBefore.minusSeconds(2));
        } catch (IOException e) {
            return false;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return Long.MAX_VALUE / 4;
        }
    }

    private static String mb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }
}
