package com.automation.core.recorder;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Writes a recording to disk in the formats the keyword engine already reads: rows in a keyword CSV
 * and {@code key = type:value} lines in the site's object-repository properties file. Existing
 * content is never rewritten except for the test case being overwritten on request.
 */
public final class ScriptWriter {

    public static final String CSV_HEADER = "testCase,stepNo,keyword,locatorKey,testData,expected,description";

    private ScriptWriter() {
    }

    /** The repository as {@code key -> raw type:value}; empty if the file does not exist yet. */
    public static Map<String, String> readRepository(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return entries;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        for (String name : properties.stringPropertyNames()) {
            entries.put(name, properties.getProperty(name));
        }
        return entries;
    }

    /** Appends the new keys of {@code specs} under a dated comment; keys already in the file are left alone. */
    public static int appendRepository(Path file, String testCase, Collection<LocatorSpec> specs) throws IOException {
        List<String> lines = new ArrayList<>();
        for (LocatorSpec spec : specs) {
            if (spec.alreadyInRepository) {
                continue;
            }
            for (Map.Entry<String, String> entry : spec.repositoryEntries().entrySet()) {
                lines.add(entry.getKey() + " = " + escapePropertyValue(entry.getValue()));
            }
        }
        if (lines.isEmpty()) {
            return 0;
        }
        StringBuilder block = new StringBuilder();
        block.append(needsLeadingNewline(file) ? "\n" : "").append("\n# --- recorded by RecorderCli: ")
            .append(testCase.replaceAll("[^A-Za-z0-9_.-]", "_")).append(" (").append(LocalDate.now()).append(") ---\n");
        for (String line : lines) {
            block.append(line).append('\n');
        }
        createParent(file);
        Files.writeString(file, block.toString(), StandardCharsets.ISO_8859_1,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return lines.size();
    }

    /** Names of the test cases already in a keyword CSV (empty if the file does not exist). */
    public static Set<String> existingTestCases(Path csv) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String[] row : readCsv(csv)) {
            if (row.length > 0 && !row[0].isBlank() && !"testCase".equalsIgnoreCase(row[0].trim())) {
                names.add(row[0].trim());
            }
        }
        return names;
    }

    /**
     * Adds {@code testCase} to the CSV, creating the file (with header) if needed.
     *
     * @param overwrite replace the test case if it already exists; otherwise that is an error
     */
    public static void writeScript(Path csv, String testCase, List<RecordedStep> steps, boolean overwrite) throws IOException {
        boolean exists = Files.exists(csv);
        List<String[]> keep = new ArrayList<>();
        if (exists) {
            for (String[] row : readCsv(csv)) {
                boolean header = row.length > 0 && "testCase".equalsIgnoreCase(row[0].trim());
                boolean sameCase = row.length > 0 && row[0].trim().equals(testCase);
                if (sameCase && !overwrite) {
                    throw new IllegalStateException("Test case '" + testCase + "' already exists in " + csv
                        + ". Pick another --name or pass --overwrite.");
                }
                if (!header && !sameCase && row.length > 0 && !row[0].isBlank()) {
                    keep.add(row);
                }
            }
        }
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (String[] row : keep) {
            out.append(joinCsv(row)).append('\n');
        }
        int stepNo = 1;
        for (RecordedStep step : steps) {
            out.append(joinCsv(new String[] {testCase, String.valueOf(stepNo++), step.keyword, step.locatorKey,
                step.testData, step.expected, step.description})).append('\n');
        }
        createParent(csv);
        Files.writeString(csv, out.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String[]> readCsv(Path csv) throws IOException {
        List<String[]> rows = new ArrayList<>();
        if (!Files.exists(csv)) {
            return rows;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(csv), StandardCharsets.UTF_8);
             CSVReader csvReader = new CSVReader(reader)) {
            String[] row;
            while ((row = csvReader.readNext()) != null) {
                if (row.length > 0 && row[0].startsWith("\uFEFF")) {
                    row[0] = row[0].substring(1);
                }
                rows.add(row);
            }
        } catch (CsvValidationException e) {
            throw new IOException("Cannot parse " + csv + ": " + e.getMessage(), e);
        }
        return rows;
    }

    private static String joinCsv(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(fields[i]));
        }
        return sb.toString();
    }

    static String csvField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
            || value.startsWith(" ") || value.endsWith(" ");
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Escapes a value for a {@code .properties} file that is read as ISO-8859-1 (which is how
     * ObjectRepository loads it): backslashes doubled, control characters flattened to a space,
     * and everything outside printable ASCII written as a {@code \\uXXXX} escape so Hindi/Gujarati
     * link text survives.
     */
    static String escapePropertyValue(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c < 0x20) {
                sb.append(' ');
            } else if (c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else if (c == ' ' && i == 0) {
                sb.append("\\ ");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean needsLeadingNewline(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(raf.length() - 1);
            return raf.read() != '\n';
        }
    }

    private static void createParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
