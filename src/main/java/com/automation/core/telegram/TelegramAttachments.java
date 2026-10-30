package com.automation.core.telegram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Chooses the files to upload: the PDF report(s), then the report ZIP when it exists, otherwise each
 * Extent {@code index.html} (self-contained - the framework enables Extent offline mode). Only files
 * modified since the run started are considered, so a stale report from an earlier run is never sent as
 * if it were this one. A file over the per-file limit is left out and explained in {@link #notes()}.
 */
public final class TelegramAttachments {

    /** One file to upload, under the name the chat will show. */
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

    public static TelegramAttachments collect(TelegramSettings settings, Instant notBefore) {
        TelegramAttachments result = new TelegramAttachments();
        if (settings.attachPdf()) {
            addPdfs(settings, notBefore, result);
        }
        if (settings.attachReports()) {
            addReports(settings, notBefore, result);
        }
        return result;
    }

    private static void addPdfs(TelegramSettings settings, Instant notBefore, TelegramAttachments result) {
        Path dir = settings.pdfDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                .filter(p -> isFresh(p, notBefore)).sorted()
                .forEach(p -> addIfFits(result, p, p.getFileName().toString(), settings.maxFileBytes()));
        } catch (IOException e) {
            result.notes.add("Could not list " + dir + ": " + e.getMessage());
        }
    }

    private static void addReports(TelegramSettings settings, Instant notBefore, TelegramAttachments result) {
        Path zip = settings.zipFile();
        if (isFresh(zip, notBefore)) {
            addIfFits(result, zip, zip.getFileName().toString(), settings.maxFileBytes());
            return;
        }
        Path dir = settings.extentDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.getFileName().toString().equals("index.html")).filter(Files::isRegularFile)
                .filter(p -> isFresh(p, notBefore)).sorted().forEach(p -> {
                    String name = dir.relativize(p).toString().replace('\\', '/').replace('/', '-');
                    addIfFits(result, p, name, settings.maxFileBytes());
                });
        } catch (IOException e) {
            result.notes.add("Could not list " + dir + ": " + e.getMessage());
        }
    }

    private static void addIfFits(TelegramAttachments result, Path file, String name, long maxBytes) {
        long size = sizeOf(file);
        if (size <= 0) {
            return; // Telegram rejects empty documents
        }
        if (size <= maxBytes) {
            result.items.add(new Item(file, name));
        } else {
            result.notes.add(name + " was not sent (" + mb(size) + " MB is over the " + mb(maxBytes) + " MB limit).");
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
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
