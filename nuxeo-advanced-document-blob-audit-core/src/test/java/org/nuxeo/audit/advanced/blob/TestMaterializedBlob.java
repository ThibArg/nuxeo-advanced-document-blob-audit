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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.ByteArrayBlob;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RuntimeFeature;

/**
 * Unit tests for {@link MaterializedBlob} (item 10).
 * <p>
 * Needs the runtime, because materialising goes through {@code Framework.createTempFile}.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(RuntimeFeature.class)
public class TestMaterializedBlob {

    protected static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

    /**
     * Temporary files built by the tests, deleted after each one.
     * <p>
     * Not {@code deleteOnExit()}: the Nuxeo guidelines rule it out, and these tests assert on
     * {@code file.exists()}, so the deletion has to happen after the assertions anyway.
     */
    protected final List<File> tempFiles = new ArrayList<>();

    @After
    public void deleteTempFiles() throws Exception {
        for (File file : tempFiles) {
            Files.deleteIfExists(file.toPath());
        }
        tempFiles.clear();
    }

    protected Blob remoteLike(String filename) {
        Blob blob = new ByteArrayBlob(CONTENT, "text/plain", StandardCharsets.UTF_8.name());
        blob.setFilename(filename);
        blob.setDigest("cafebabe");
        return blob;
    }

    @Test
    public void testNonLocalBlobIsCopiedToAFile() throws Exception {
        try (MaterializedBlob materialized = MaterializedBlob.of(remoteLike("f.txt"))) {
            File file = materialized.blob().getFile();

            assertNotNull("a non file-backed blob must be materialised", file);
            assertTrue(file.exists());
            assertEquals("hello", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
    }

    /** Extractors are selected on the mime type, so losing it would silently change the result. */
    @Test
    public void testMetadataIsCarriedOver() {
        try (MaterializedBlob materialized = MaterializedBlob.of(remoteLike("f.txt"))) {
            Blob local = materialized.blob();

            assertEquals("text/plain", local.getMimeType());
            assertEquals(StandardCharsets.UTF_8.name(), local.getEncoding());
            assertEquals("f.txt", local.getFilename());
            assertEquals("cafebabe", local.getDigest());
        }
    }

    @Test
    public void testTemporaryCopyIsDeletedOnClose() {
        File file;
        try (MaterializedBlob materialized = MaterializedBlob.of(remoteLike("f.txt"))) {
            file = materialized.blob().getFile();
            assertTrue(file.exists());
        }

        assertFalse("the temporary copy must not be left behind", file.exists());
    }

    /**
     * The one thing that must never happen: taking ownership of a file belonging to the blob store
     * and deleting it at the end of the diff.
     */
    @Test
    public void testAlreadyLocalBlobIsAPassThroughAndItsFileSurvives() throws Exception {
        File file = Framework.createTempFile("blobdiff-test-", ".txt");
        tempFiles.add(file);
        Files.write(file.toPath(), CONTENT);
        Blob source = new FileBlob(file, "text/plain");

        try (MaterializedBlob materialized = MaterializedBlob.of(source)) {
            assertSame("an already local blob must not be copied", source, materialized.blob());
        }

        assertTrue("the source file must never be deleted", file.exists());
    }

    @Test
    public void testAlreadyLocalBlobIsNotRead() throws Exception {
        File file = Framework.createTempFile("blobdiff-test-", ".txt");
        tempFiles.add(file);
        Files.write(file.toPath(), CONTENT);
        AtomicInteger reads = new AtomicInteger();
        Blob source = new FileBlob(file, "text/plain") {
            private static final long serialVersionUID = 1L;

            @Override
            public InputStream getStream() throws java.io.IOException {
                reads.incrementAndGet();
                return super.getStream();
            }
        };

        try (MaterializedBlob materialized = MaterializedBlob.of(source)) {
            assertNotNull(materialized.blob());
        }

        assertEquals("a local blob must not be streamed just to be materialised", 0, reads.get());
    }

    @Test
    public void testNullBlobIsSupported() {
        try (MaterializedBlob materialized = MaterializedBlob.of(null)) {
            assertNull(materialized.blob());
        }
    }
}
