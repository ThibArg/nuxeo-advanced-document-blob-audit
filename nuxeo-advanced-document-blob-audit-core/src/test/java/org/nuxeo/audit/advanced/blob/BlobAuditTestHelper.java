/*
 * (C) Copyright 2026 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nuxeo.audit.advanced.blob;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.ByteArrayBlob;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.runtime.api.Framework;

/**
 * Fixture builders shared by the tests.
 * <p>
 * Spreadsheets are generated in memory with POI rather than shipped as binary fixtures: the test
 * data stays readable and reviewable in the source, which matters a lot when an assertion on a cell
 * count starts failing.
 *
 * @since 2025.1
 */
public class BlobAuditTestHelper {

    public static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private BlobAuditTestHelper() {
        // utility class
    }

    /**
     * Temporary files handed out by {@link #onDisk(Blob)}, deleted by {@link #deleteTempFiles()}.
     * <p>
     * Not {@code deleteOnExit()}: the Nuxeo guidelines rule it out - deletion only happens on a
     * normal JVM exit, the files live far longer than needed, and nothing checks what is being
     * deleted. {@code Framework.trackFile} is the prescribed replacement, but it resolves
     * {@code EventService} and therefore needs a running runtime, which the pure-JUnit extractor
     * tests do not have. Deterministic cleanup in an {@code @After} is what is left, and it is
     * stricter than either.
     *
     * @since 2025.1
     */
    protected static final List<File> TEMP_FILES = Collections.synchronizedList(new ArrayList<>());

    public static Blob blob(byte[] bytes, String mimeType, String filename) throws IOException {
        Blob blob = new ByteArrayBlob(bytes);
        blob.setMimeType(mimeType);
        blob.setFilename(filename);
        return blob;
    }

    public static Blob textBlob(String content, String mimeType, String filename) throws IOException {
        Blob blob = new ByteArrayBlob(content.getBytes(StandardCharsets.UTF_8), mimeType,
                StandardCharsets.UTF_8.name());
        blob.setFilename(filename);
        return blob;
    }

    /**
     * Same bytes, but backed by a real file, so {@code blob.getFile()} is not {@code null}.
     * <p>
     * The extractors read POI formats from the file when there is one - random access, read only -
     * and fall back to the stream otherwise. Every other fixture builder here produces an in-memory
     * blob, so the file branch, which is the one that actually runs in production behind
     * {@code MaterializedBlob}, would never be exercised without this.
     * <p>
     * The file goes to the Nuxeo temporary directory through {@link Framework#createTempFile}, not
     * to {@code java.io.tmpdir} as resolved at JVM start. Call {@link #deleteTempFiles()} from an
     * {@code @After} to release it.
     *
     * @since 2025.1
     */
    public static Blob onDisk(Blob source) throws IOException {
        File file = Framework.createTempFile("blobaudit-test-", "-" + source.getFilename());
        TEMP_FILES.add(file);
        try (InputStream in = source.getStream()) {
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new FileBlob(file, source.getMimeType(), source.getEncoding(), source.getFilename(), null);
    }

    /**
     * Deletes every temporary file handed out by {@link #onDisk(Blob)} so far.
     *
     * @since 2025.1
     */
    public static void deleteTempFiles() throws IOException {
        synchronized (TEMP_FILES) {
            for (File file : TEMP_FILES) {
                Files.deleteIfExists(file.toPath());
            }
            TEMP_FILES.clear();
        }
    }

    /**
     * Builds a single-sheet workbook from a {@code "A1" -> value} map. Values that parse as a double
     * are written as numeric cells, values starting with {@code =} as formulas, anything else as
     * strings.
     */
    public static Blob xlsx(String sheetName, Map<String, String> cells, String filename) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fillSheet(workbook.createSheet(sheetName), cells);
            workbook.write(out);
            return blob(out.toByteArray(), XLSX_MIME, filename);
        }
    }

    /** Multi-sheet variant: sheet name to cell map, insertion order preserved. */
    public static Blob xlsx(Map<String, Map<String, String>> sheets, String filename) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, Map<String, String>> entry : sheets.entrySet()) {
                fillSheet(workbook.createSheet(entry.getKey()), entry.getValue());
            }
            workbook.write(out);
            return blob(out.toByteArray(), XLSX_MIME, filename);
        }
    }

    protected static void fillSheet(Sheet sheet, Map<String, String> cells) {
        for (Map.Entry<String, String> entry : cells.entrySet()) {
            CellReference reference = new CellReference(entry.getKey());
            Row row = sheet.getRow(reference.getRow());
            if (row == null) {
                row = sheet.createRow(reference.getRow());
            }
            Cell cell = row.createCell(reference.getCol());
            String value = entry.getValue();
            if (value.startsWith("=")) {
                cell.setCellFormula(value.substring(1));
            } else {
                try {
                    cell.setCellValue(Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    cell.setCellValue(value);
                }
            }
        }
    }

    /** Convenience builder preserving insertion order. */
    public static Map<String, String> cells(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of arguments");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    /** Builds positional content from plain lines. */
    public static DiffableContent positional(String... lines) {
        List<ContentLine> content = new ArrayList<>();
        for (String line : lines) {
            content.add(ContentLine.of(line));
        }
        return DiffableContent.positional(content, false);
    }

    /** Builds keyed content from {@code key, value, key, value...} pairs. */
    public static DiffableContent keyed(String... keyValuePairs) {
        List<ContentLine> content = new ArrayList<>();
        for (Map.Entry<String, String> entry : cells(keyValuePairs).entrySet()) {
            content.add(ContentLine.of(entry.getKey(), entry.getValue()));
        }
        return DiffableContent.keyed(content, false);
    }
}
