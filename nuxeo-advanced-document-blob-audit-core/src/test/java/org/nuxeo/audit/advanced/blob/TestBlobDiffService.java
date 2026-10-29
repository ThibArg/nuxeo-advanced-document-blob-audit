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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.DOCX_MIME;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.customersJson;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.productsXml;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.extractor.ConverterTextExtractor;
import org.nuxeo.audit.advanced.blob.extractor.PlainTextExtractor;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Runtime tests for {@link BlobDiffService}: extractor selection, guardrails, container layout and
 * security, and persistence of the {@code BlobDiff} document.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffService {

    @Inject
    protected CoreSession session;

    @Inject
    protected BlobDiffService blobDiffService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ---------------------------------------------------------------- service */

    @Test
    public void testServiceIsRegistered() {
        assertNotNull(blobDiffService);
        assertTrue("test config must enable the feature", blobDiffService.getConfig().isEnabled());
    }

    /**
     * API-02 (code review 2026-10): {@code stop()} emptied the extractor lists, while a work still
     * running past the WorkManager drain kept extracting. It then filed the binary as an unsupported
     * format, a status {@code BlobDiff.Retry} refuses: a permanent, mislabelled hole.
     */
    @Test
    public void testAStoppingComponentKeepsItsExtractors() throws Exception {
        BlobDiffComponent component = (BlobDiffComponent) blobDiffService;
        component.stop(null);
        try {
            assertNotNull(component.findExtractor("text/plain"));
            assertEquals(DiffEligibility.ELIGIBLE,
                    component.getEligibility("File", "file:content", textBlob("x", "text/plain", "x.txt")));
        } finally {
            component.start(null);
        }
    }

    @Test
    public void testSpreadsheetExtractorIsSelected() throws Exception {
        DiffableContent content = blobDiffService.extract(xlsx("Sheet1", cells("A1", "hello"), "f.xlsx"));

        assertNotNull(content);
        assertTrue("a spreadsheet must produce keyed content", content.keyed());
    }

    @Test
    public void testPlainTextExtractorIsSelected() throws Exception {
        DiffableContent content = blobDiffService.extract(textBlob("a\nb", "text/plain", "f.txt"));

        assertNotNull(content);
        assertFalse("text must produce positional content", content.keyed());
    }

    /** RES-07 (code review 2026-10): the configured {@code maxValueLength} bounds values at extraction. */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallvalue-config.xml")
    public void testTheConfiguredMaxValueLengthReachesTheExtractors() throws Exception {
        DiffableContent content = blobDiffService.extract(textBlob("x".repeat(10_000), "text/plain", "f.txt"));

        assertEquals(200, blobDiffService.getConfig().getMaxValueLength());
        assertEquals(1, content.size());
        assertTrue(content.lines().get(0).value().length() <= 200);
    }

    /**
     * API-01 (code review 2026-10): a partial contribution, through the real registry. It only sets
     * {@code docTypes} and one property of the {@code converter} extractor; the feature must stay on,
     * the bounds and xpaths of the earlier contribution must survive, and DOCX must stay supported.
     */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-partial-config.xml")
    public void testAPartialContributionOnlyChangesWhatItSets() {
        BlobDiffConfigDescriptor config = blobDiffService.getConfig();

        assertTrue(config.isEnabled());
        assertEquals(List.of("File"), config.getDocTypes());
        assertEquals(List.of("file:content"), config.getXPaths());
        assertEquals(1048576, config.getMaxBlobSize());
        assertEquals(1000, config.getMaxLines());
        assertTrue(((BlobDiffComponent) blobDiffService).findExtractor(DOCX_MIME) instanceof ConverterTextExtractor);
    }

    /**
     * No extractor means no diff, and a {@code null} result the caller must handle. This is now the
     * <b>only</b> meaning of {@code null}: the counterpart of the test just below.
     */
    @Test
    public void testUnsupportedMimeTypeYieldsNoExtractor() throws Exception {
        Blob blob = textBlob("binary-ish", "application/x-unknown-binary", "f.bin");

        assertNull(blobDiffService.extract(blob));
        assertNull(blobDiffService.diff(blob, blob));
        assertFalse(blobDiffService.isDiffable("File", "file:content", blob));
    }

    /**
     * A failing extraction is an incident, not an unsupported format.
     * <p>
     * The previous version of this test asserted the opposite ("a corrupted blob must not throw"),
     * on the premise that {@code extract} runs on the synchronous listener path. It does not: the
     * only callers are {@code BlobDiffComponent#diffLocal} and, through it, {@code BlobDiffWork},
     * which turns the raised failure into {@code error} - the one status {@code BlobDiff.Retry}
     * accepts.
     */
    @Test
    public void testCorruptedSpreadsheetRaisesInsteadOfLookingUnsupported() throws Exception {
        Blob corrupted = BlobAuditTestHelper.blob("not a zip at all".getBytes(StandardCharsets.UTF_8),
                BlobAuditTestHelper.XLSX_MIME, "corrupt.xlsx");

        assertThrows(NuxeoException.class, () -> blobDiffService.extract(corrupted));
        assertThrows("the failure must reach the caller through diff() too", NuxeoException.class,
                () -> blobDiffService.diff(corrupted, corrupted));
    }

    /* ------------------------------------------------------------- guardrails */

    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallblob-config.xml")
    public void testBlobLargerThanMaxSizeIsNotDiffable() throws Exception {
        // this override caps maxBlobSize at 512 bytes
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2048) {
            sb.append("padding padding padding\n");
        }

        assertFalse(blobDiffService.isDiffable("File", "file:content",
                textBlob(sb.toString(), "text/plain", "big.txt")));
        assertTrue("a small blob must still pass",
                blobDiffService.isDiffable("File", "file:content", textBlob("tiny", "text/plain", "small.txt")));
    }

    @Test
    public void testXPathOutsideTheConfiguredListIsNotDiffable() throws Exception {
        Blob blob = textBlob("hello", "text/plain", "f.txt");

        assertTrue(blobDiffService.isDiffable("File", "file:content", blob));
        assertFalse(blobDiffService.isDiffable("File", "files:files/0/file", blob));
    }

    @Test
    public void testNullBlobIsNotDiffable() {
        assertFalse(blobDiffService.isDiffable("File", "file:content", null));
    }

    /* ------------------------------------------------------------ eligibility */

    /**
     * isDiffable collapses "out of scope" and "changed but not diffable" into the same false;
     * getEligibility must keep them apart, which is what lets the listener audit the second case.
     */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallblob-config.xml")
    public void testEligibilityDistinguishesTheReasons() throws Exception {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2048) {
            sb.append("padding padding padding\n");
        }

        assertEquals(DiffEligibility.TOO_LARGE, blobDiffService.getEligibility("File", "file:content",
                textBlob(sb.toString(), "text/plain", "big.txt")));
        assertEquals(DiffEligibility.UNSUPPORTED_TYPE, blobDiffService.getEligibility("File", "file:content",
                textBlob("tiny", "application/x-unknown-binary", "f.bin")));
        assertEquals(DiffEligibility.ELIGIBLE,
                blobDiffService.getEligibility("File", "file:content", textBlob("tiny", "text/plain", "f.txt")));
        assertEquals(DiffEligibility.NOT_APPLICABLE, blobDiffService.getEligibility("File", "file:content", null));
        assertEquals(DiffEligibility.NOT_APPLICABLE, blobDiffService.getEligibility("File", "files:files/0/file",
                textBlob("tiny", "text/plain", "f.txt")));
    }

    /**
     * NOT_APPLICABLE wins over everything: it does not depend on the blob, so the state of the
     * other side must never turn it into a reported skip.
     */
    @Test
    public void testEligibilityCombinationPrecedence() {
        assertEquals(DiffEligibility.NOT_APPLICABLE,
                DiffEligibility.combine(DiffEligibility.NOT_APPLICABLE, DiffEligibility.TOO_LARGE));
        assertEquals(DiffEligibility.TOO_LARGE,
                DiffEligibility.combine(DiffEligibility.UNSUPPORTED_TYPE, DiffEligibility.TOO_LARGE));
        assertEquals(DiffEligibility.UNSUPPORTED_TYPE,
                DiffEligibility.combine(DiffEligibility.ELIGIBLE, DiffEligibility.UNSUPPORTED_TYPE));
        assertEquals(DiffEligibility.UNSUPPORTED_TYPE,
                DiffEligibility.combine(DiffEligibility.FORMAT_CHANGED, DiffEligibility.UNSUPPORTED_TYPE));
        assertEquals(DiffEligibility.FORMAT_CHANGED,
                DiffEligibility.combine(DiffEligibility.ELIGIBLE, DiffEligibility.FORMAT_CHANGED));
        assertEquals(DiffEligibility.ELIGIBLE,
                DiffEligibility.combine(DiffEligibility.ELIGIBLE, DiffEligibility.ELIGIBLE));

        assertFalse(DiffEligibility.ELIGIBLE.isReportable());
        assertFalse(DiffEligibility.NOT_APPLICABLE.isReportable());
        assertTrue(DiffEligibility.TOO_LARGE.isReportable());
        assertTrue(DiffEligibility.UNSUPPORTED_TYPE.isReportable());
        assertTrue(DiffEligibility.FORMAT_CHANGED.isReportable());
    }

    /**
     * Each extractor contribution is a comparability class: a pair read by two of them is not
     * compared, a pair read by one is, whatever the mime types. The verdicts of the sides come first.
     */
    @Test
    public void testThePairEligibilityComparesTheExtractorContributions() throws Exception {
        assertEquals(DiffEligibility.FORMAT_CHANGED, pairEligibility(BlobAuditTestHelper.XLSX_MIME, DOCX_MIME));
        assertEquals("a PDF rendition is not comparable with its Word source", DiffEligibility.FORMAT_CHANGED,
                pairEligibility(DOCX_MIME, "application/pdf"));
        assertEquals(DiffEligibility.FORMAT_CHANGED, pairEligibility("application/vnd.ms-powerpoint", DOCX_MIME));
        assertEquals(DiffEligibility.FORMAT_CHANGED, pairEligibility("text/csv", BlobAuditTestHelper.XLSX_MIME));

        assertEquals(DiffEligibility.ELIGIBLE, pairEligibility("application/msword", DOCX_MIME));
        assertEquals(DiffEligibility.ELIGIBLE,
                pairEligibility("application/vnd.ms-excel", BlobAuditTestHelper.XLSX_MIME));
        assertEquals(DiffEligibility.ELIGIBLE, pairEligibility("text/plain", "application/json"));
        assertEquals(DiffEligibility.ELIGIBLE, pairEligibility("application/pdf", "application/pdf"));

        assertEquals("an unsupported side wins over the format change", DiffEligibility.UNSUPPORTED_TYPE,
                pairEligibility("application/x-unknown-binary", DOCX_MIME));
        assertEquals(DiffEligibility.NOT_APPLICABLE, blobDiffService.getEligibility("File", "files:files/0/file",
                textBlob("x", "text/plain", "f.txt"), textBlob("y", DOCX_MIME, "f.docx")));
    }

    protected DiffEligibility pairEligibility(String oldMimeType, String newMimeType) throws Exception {
        return blobDiffService.getEligibility("File", "file:content", textBlob("old", oldMimeType, "old.bin"),
                textBlob("new", newMimeType, "new.bin"));
    }

    /* ------------------------------------------------------- replaced content */

    /**
     * Item 62: a text file replaced by an unrelated one of another format is flagged, and keeps its
     * full body for a caller that wants it - only {@code BlobDiffWork} leaves it out.
     */
    @Test
    public void testATextFileReplacedByAnotherFormatIsFlagged() throws Exception {
        DiffResult result = blobDiffService.diff(textBlob(customersJson(10), "application/json", "data.json"),
                textBlob(productsXml(10), "application/xml", "data.xml"));

        assertTrue(result.replaced());
        assertEquals(0d, result.similarity(), 0d);
        assertFalse("the service still computes the whole body", result.unified().isEmpty());
    }

    /** The rule is about a file replaced by another one, not about how much an author rewrote. */
    @Test
    public void testARewriteInTheSameFormatIsNeverFlagged() throws Exception {
        DiffResult result = blobDiffService.diff(textBlob(customersJson(10), "application/json", "a.json"),
                textBlob(productsXml(10), "application/json; charset=UTF-8", "b.json"));

        assertFalse(result.replaced());
        assertEquals(0d, result.similarity(), 0d);
    }

    @Test
    public void testAFileEditedUnderAnotherFormatIsNotFlagged() throws Exception {
        String edited = customersJson(10).replace("Customer number 3", "Customer renamed");

        DiffResult result = blobDiffService.diff(textBlob(customersJson(10), "text/plain", "data.txt"),
                textBlob(edited, "application/json", "data.json"));

        assertFalse(result.replaced());
        assertEquals(1, result.changed());
        assertTrue(result.similarity() > 0.9);
    }

    /** A percentage means little on a handful of lines: under minSimilarityLines, always diffed. */
    @Test
    public void testASmallFileIsNeverFlagged() throws Exception {
        DiffResult result = blobDiffService.diff(textBlob("a\nb\nc\nd", "text/plain", "f.txt"),
                textBlob("w\nx\ny\nz", "text/csv", "f.csv"));

        assertFalse(result.replaced());
        assertEquals(0d, result.similarity(), 0d);
    }

    /** Only the contributions that set minSimilarity apply it: plainText alone, as shipped. */
    @Test
    public void testOnlyThePlainTextContributionSetsTheRule() {
        BlobDiffComponent component = (BlobDiffComponent) blobDiffService;

        assertEquals(0.1, component.findResolved(component.extractors, "application/json").minSimilarity(), 0d);
        assertEquals(5, component.findResolved(component.extractors, "text/plain").minSimilarityLines());
        for (String mimeType : List.of(DOCX_MIME, "application/pdf", BlobAuditTestHelper.XLSX_MIME,
                BlobAuditTestHelper.PPTX_MIME, "application/vnd.ms-powerpoint")) {
            assertEquals(mimeType, 0d, component.findResolved(component.extractors, mimeType).minSimilarity(), 0d);
        }
    }

    /** The setting a deployment uses to keep every full diff, through the real registry. */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-keepalldiffs-config.xml")
    public void testMinSimilarityZeroKeepsEveryDiff() throws Exception {
        DiffResult result = blobDiffService.diff(textBlob(customersJson(10), "application/json", "data.json"),
                textBlob(productsXml(10), "application/xml", "data.xml"));

        assertFalse(result.replaced());
        assertEquals("the score is still measured", 0d, result.similarity(), 0d);
        assertTrue("the partial override must keep the extractor",
                ((BlobDiffComponent) blobDiffService).findExtractor("application/json") instanceof PlainTextExtractor);
    }

    /* -------------------------------------------------------------- container */

    @Test
    public void testContainerIsPartitionedByDate() {
        DocumentModel container = blobDiffService.getOrCreateContainer(session, date(2026, Calendar.SEPTEMBER, 25));

        assertEquals("/" + BlobAuditConstants.CONTAINER_NAME + "/2026/09/25", container.getPathAsString());
        assertTrue(session.exists(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME)));
    }

    /** Two diffs on the same day must land in the same folder, not create duplicates. */
    @Test
    public void testContainerCreationIsIdempotent() {
        Date date = date(2026, Calendar.SEPTEMBER, 25);

        DocumentModel first = blobDiffService.getOrCreateContainer(session, date);
        DocumentModel second = blobDiffService.getOrCreateContainer(session, date);

        assertEquals(first.getId(), second.getId());
    }

    @Test
    public void testDifferentDaysGetDifferentFolders() {
        DocumentModel sept = blobDiffService.getOrCreateContainer(session, date(2026, Calendar.SEPTEMBER, 25));
        DocumentModel oct = blobDiffService.getOrCreateContainer(session, date(2026, Calendar.OCTOBER, 1));

        assertEquals("/change-diff/2026/09/25", sept.getPathAsString());
        assertEquals("/change-diff/2026/10/01", oct.getPathAsString());
    }

    /**
     * The whole point of using a dedicated document rather than a facet on the source: the diffs get
     * their own, stricter ACL. Inheritance must be blocked, otherwise anyone able to read the source
     * document would read every past version of its content.
     */
    @Test
    public void testRootContainerBlocksInheritanceAndRestrictsRead() {
        blobDiffService.getOrCreateContainer(session, new Date());
        DocumentModel root = session.getDocument(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME));

        ACP acp = session.getACP(root.getRef());
        ACL localAcl = acp.getACL(ACL.LOCAL_ACL);

        assertNotNull("a local ACL must be set on the diff container", localAcl);
        assertTrue("inheritance must be blocked",
                Arrays.stream(localAcl.getACEs())
                      .anyMatch(ace -> SecurityConstants.EVERYONE.equals(ace.getUsername())
                              && SecurityConstants.EVERYTHING.equals(ace.getPermission()) && !ace.isGranted()));
        String group = blobDiffService.getConfig().getAuditorsGroup();
        for (String permission : new String[] { SecurityConstants.READ, SecurityConstants.REMOVE,
                SecurityConstants.REMOVE_CHILDREN }) {
            assertTrue("auditors must be granted " + permission,
                    Arrays.stream(localAcl.getACEs())
                          .anyMatch(ace -> group.equals(ace.getUsername()) && permission.equals(ace.getPermission())
                                  && ace.isGranted()));
        }
    }

    @Test
    public void testContainerIsHiddenFromNavigation() {
        blobDiffService.getOrCreateContainer(session, new Date());
        DocumentModel root = session.getDocument(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME));

        assertTrue(root.hasFacet("HiddenInNavigation"));
    }

    /* ------------------------------------------------------------ persistence */

    @Test
    public void testCreateDiffDocumentStoresSummaryAndBlob() throws Exception {
        DocumentModel source = session.createDocument(session.createDocumentModel("/", "source", "File"));
        session.save();

        Blob before = xlsx("Sheet1", cells("A1", "100"), "before.xlsx");
        Blob after = xlsx("Sheet1", cells("A1", "120"), "after.xlsx");
        DiffResult result = blobDiffService.diff(before, after);
        assertEquals(1, result.changed());

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", before, after,
                "jdoe", new Date(), result, BlobAuditConstants.STATUS_OK, "corr-42");
        session.save();

        assertEquals(BlobAuditConstants.DIFF_DOCTYPE, diffDoc.getType());
        assertEquals(source.getId(), diffDoc.getPropertyValue(BlobAuditConstants.XP_SOURCE_ID));
        assertEquals("file:content", diffDoc.getPropertyValue(BlobAuditConstants.XP_XPATH));
        assertEquals("jdoe", diffDoc.getPropertyValue(BlobAuditConstants.XP_USER));
        assertEquals("corr-42", diffDoc.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID));
        assertEquals(BlobAuditConstants.STATUS_OK, diffDoc.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals("1 modification", diffDoc.getPropertyValue(BlobAuditConstants.XP_SUMMARY));
        assertEquals(Long.valueOf(1), diffDoc.getPropertyValue(BlobAuditConstants.XP_CHANGED));
        assertEquals(Long.valueOf(0), diffDoc.getPropertyValue(BlobAuditConstants.XP_ADDED));

        // the diff itself is a blob, deliberately kept out of the document record and full-text
        Blob diffBlob = (Blob) diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB);
        assertNotNull(diffBlob);
        assertTrue(diffBlob.getString().contains("Sheet1!A1"));

        assertTrue("diffs must be hidden", diffDoc.hasFacet("HiddenInNavigation"));
        assertTrue(diffDoc.getPathAsString().startsWith("/" + BlobAuditConstants.CONTAINER_NAME + "/"));
    }

    /** A skipped diff must still be traced, with a status explaining why nothing was compared. */
    @Test
    public void testCreateDiffDocumentWithSkippedStatusHasNoBlob() {
        DocumentModel source = session.createDocument(session.createDocumentModel("/", "source-skipped", "File"));
        session.save();

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", null, null,
                "jdoe", new Date(), null, BlobAuditConstants.STATUS_SKIPPED_TYPE, "corr-43");
        session.save();

        assertEquals(BlobAuditConstants.STATUS_SKIPPED_TYPE,
                diffDoc.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertNull(diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB));
    }

    /** An empty diff is recorded, but carries no blob: there is nothing to store. */
    @Test
    public void testIdenticalContentProducesNoDiffBlob() throws Exception {
        DocumentModel source = session.createDocument(session.createDocumentModel("/", "source-same", "File"));
        session.save();

        Blob blob = xlsx("Sheet1", cells("A1", "same"), "f.xlsx");
        DiffResult result = blobDiffService.diff(blob, blob);
        assertTrue(result.isEmpty());

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", blob, blob,
                "jdoe", new Date(), result, BlobAuditConstants.STATUS_OK, "corr-44");
        session.save();

        assertNull(diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB));
        assertEquals("No textual change detected", diffDoc.getPropertyValue(BlobAuditConstants.XP_SUMMARY));
    }

    /** Digests are stored as evidence and are what lets a rename be told apart from an edit. */
    @Test
    public void testDigestsArePersisted() throws Exception {
        DocumentModel source = session.createDocumentModel("/", "source-digest", "File");
        source.setPropertyValue("file:content", (Serializable) xlsx("Sheet1", cells("A1", "v1"), "v1.xlsx"));
        source = session.createDocument(source);
        session.save();
        txFeature.nextTransaction();

        Blob stored = (Blob) session.getDocument(source.getRef()).getPropertyValue("file:content");
        assertNotNull("a stored blob must expose a digest", stored.getDigest());

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", stored, stored,
                "jdoe", new Date(), null, BlobAuditConstants.STATUS_OK, "corr-45");
        session.save();

        assertEquals(stored.getDigest(), diffDoc.getPropertyValue(BlobAuditConstants.XP_OLD_DIGEST));
        assertEquals(stored.getDigest(), diffDoc.getPropertyValue(BlobAuditConstants.XP_NEW_DIGEST));
    }

    /** In UTC, the zone the container path is rendered in: east of UTC+10, 10:00 local is the day before. */
    protected Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month, day, 10, 0, 0);
        return calendar.getTime();
    }
}
