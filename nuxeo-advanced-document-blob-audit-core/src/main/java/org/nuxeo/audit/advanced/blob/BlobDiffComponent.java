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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_TYPES_DIRECTORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.LABEL_EVENT_BLOB_MODIFIED;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.repository.RepositoryInitializationHandler;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Registry of {@link BlobContentExtractor} implementations plus the {@link BlobDiffService}
 * implementation. Also registers {@link BlobDiffRepositoryInit} and the {@code blobContentModified}
 * audit event type.
 *
 * @since 2025.1
 */
public class BlobDiffComponent extends DefaultComponent implements BlobDiffService {

    private static final Logger log = LogManager.getLogger(BlobDiffComponent.class);

    public static final String XP_EXTRACTORS = "extractors";

    /**
     * Image inventory extractors, used when {@code imageInventory} is on. A separate point on
     * purpose: both an image extractor and a text extractor run on the same blob, so they cannot
     * compete for the same mime type in a single, order-based selection.
     *
     * @since 2025.1
     */
    public static final String XP_IMAGE_EXTRACTORS = "imageExtractors";

    public static final String XP_CONFIG = "config";

    /**
     * The text extractors, by order. Written by {@link #start}, read by the {@code blobDiff} works:
     * {@code volatile}, and never modified once published, so that a work sees a complete list and
     * the extractors initialised before it, even when a restart replaces it while works drain.
     */
    protected volatile List<ResolvedExtractor> extractors;

    /** The image inventory extractors, by order; published like {@link #extractors}. */
    protected volatile List<ResolvedExtractor> imageExtractors;

    /**
     * Creates the restricted container at repository initialisation, installed while the component
     * runs.
     *
     * @since 2025.1
     */
    protected RepositoryInitializationHandler repositoryInitHandler;

    /**
     * One enabled contribution, instantiated. {@code minSimilarity} and {@code minSimilarityLines}
     * are its {@link ExtractorDescriptor#MIN_SIMILARITY} rule, parsed once; {@code 0} disables it.
     */
    protected record ResolvedExtractor(ExtractorDescriptor descriptor, BlobContentExtractor extractor,
            double minSimilarity, int minSimilarityLines) {
    }

    @Override
    public void start(ComponentContext context) {
        super.start(context);
        extractors = resolve(XP_EXTRACTORS);
        imageExtractors = resolve(XP_IMAGE_EXTRACTORS);
        registerAuditEventType();
        repositoryInitHandler = new BlobDiffRepositoryInit();
        repositoryInitHandler.install();
    }

    /**
     * Adds {@code blobContentModified} to the platform {@code eventTypes} vocabulary.
     * <p>
     * Done programmatically on purpose. A declarative {@code <dataFile>} contribution does not add to
     * the platform's data, it <b>replaces</b> it: {@code dataFileName} is single-valued,
     * {@code BaseDirectoryDescriptor#merge} overwrites it, and {@code DirectoryRegistry} restarts the
     * effective descriptor from the template for every contribution carrying {@code extends}. A
     * greenfield instance would end up with this single row and lose the platform's 51.
     * <p>
     * A runtime with no {@code eventTypes} directory is skipped in silence, because the directory is
     * genuinely absent rather than merely late: {@code DirectoryServiceImpl} starts at order 97 and
     * this component at the default 1000, so the registry is populated and initialized by the time
     * this runs, and {@code getDirectory} answers {@code null} instead of throwing. That happens on a
     * distribution without {@code org.nuxeo.ecm.default.config}, which is where the
     * {@code template-vocabulary} the platform's {@code eventTypes} extends comes from. The only
     * consequence is a missing label in the audit filter, which is not worth an error.
     *
     * @since 2025.1
     */
    protected void registerAuditEventType() {
        var ds = Framework.getService(DirectoryService.class);
        if (ds == null || ds.getDirectory(EVENT_TYPES_DIRECTORY) == null) {
            log.debug("No {} directory on this runtime; {} will not appear in the audit filter",
                    EVENT_TYPES_DIRECTORY, EVENT_BLOB_MODIFIED);
            return;
        }
        try {
            TransactionHelper.runInTransaction(() -> Framework.doPrivileged(() -> {
                try (Session session = ds.open(EVENT_TYPES_DIRECTORY)) {
                    if (session.hasEntry(EVENT_BLOB_MODIFIED)) {
                        return;
                    }
                    session.createEntry(Map.of("id", EVENT_BLOB_MODIFIED, //
                            "label", LABEL_EVENT_BLOB_MODIFIED, //
                            "obsolete", 0L, //
                            "ordering", 10_000_000L));
                }
            }));
        } catch (RuntimeException e) { // NOSONAR - never break startup over a vocabulary row
            log.error("Cannot register the {} event type; it will not appear in the audit filter",
                    EVENT_BLOB_MODIFIED, e);
        }
    }

    protected List<ResolvedExtractor> resolve(String extensionPoint) {
        List<ResolvedExtractor> resolved = new ArrayList<>();
        for (ExtractorDescriptor descriptor : this.<ExtractorDescriptor> getDescriptors(extensionPoint)) {
            if (!descriptor.isEnabled()) {
                continue;
            }
            try {
                BlobContentExtractor extractor = descriptor.getKlass().getDeclaredConstructor().newInstance();
                Map<String, String> properties = new HashMap<>(descriptor.getProperties());
                properties.putIfAbsent(BlobContentExtractor.MAX_VALUE_LENGTH,
                        String.valueOf(getConfig().getMaxValueLength()));
                extractor.init(properties);
                resolved.add(new ResolvedExtractor(descriptor, extractor, minSimilarity(descriptor),
                        minSimilarityLines(descriptor)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // RuntimeException included on purpose: init() parses contributed properties
                // (ConverterTextExtractor parses minLineLength), and a typo in an XML contribution
                // must cost this one extractor, not fail the whole component start.
                log.error("Cannot instantiate blob diff extractor: {}", descriptor.getId(), e);
            }
        }
        resolved.sort(Comparator.comparingInt(r -> r.descriptor().getOrder()));
        return List.copyOf(resolved);
    }

    /**
     * Parses the {@link ExtractorDescriptor#MIN_SIMILARITY} property of a contribution, apart from
     * {@code init()} and leniently: a typo in this property must cost the rule, not the extractor,
     * whose formats would otherwise all become unsupported.
     *
     * @return the threshold, {@code 0} when absent or invalid, which disables the rule
     */
    protected static double minSimilarity(ExtractorDescriptor descriptor) {
        String value = descriptor.getProperties().get(ExtractorDescriptor.MIN_SIMILARITY);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (parsed >= 0 && parsed <= 1) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        log.error("Invalid {} '{}' on blob diff extractor {}: expected a number between 0 and 1, rule disabled",
                ExtractorDescriptor.MIN_SIMILARITY, value, descriptor.getId());
        return 0;
    }

    protected static int minSimilarityLines(ExtractorDescriptor descriptor) {
        String value = descriptor.getProperties().get(ExtractorDescriptor.MIN_SIMILARITY_LINES);
        if (value == null || value.isBlank()) {
            return ExtractorDescriptor.DEFAULT_MIN_SIMILARITY_LINES;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        log.error("Invalid {} '{}' on blob diff extractor {}: expected a positive integer, using {}",
                ExtractorDescriptor.MIN_SIMILARITY_LINES, value, descriptor.getId(),
                ExtractorDescriptor.DEFAULT_MIN_SIMILARITY_LINES);
        return ExtractorDescriptor.DEFAULT_MIN_SIMILARITY_LINES;
    }

    /**
     * Clears the blob xpath templates of {@link VersionBlobPairs}, which a hot reload can make stale
     * by redefining the schemas they are derived from, and uninstalls the repository initialization
     * handler. The extractor lists are kept: a work still running past the WorkManager drain needs
     * them.
     */
    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        super.stop(context);
        VersionBlobPairs.clearCache();
        if (repositoryInitHandler != null) {
            repositoryInitHandler.uninstall();
            repositoryInitHandler = null;
        }
    }

    @Override
    public BlobDiffConfigDescriptor getConfig() {
        BlobDiffConfigDescriptor config = getDescriptor(XP_CONFIG, BlobDiffConfigDescriptor.DEFAULT_ID);
        return config == null ? new BlobDiffConfigDescriptor() : config;
    }

    /** Returns the verdict for one side of a pair; {@link #getEligibility(String, String, Blob, Blob)} combines two. */
    protected DiffEligibility getEligibility(String docType, String xpath, Blob blob) {
        BlobDiffConfigDescriptor config = getConfig();
        if (!config.isEnabled() || blob == null) {
            return DiffEligibility.NOT_APPLICABLE;
        }
        if (!config.acceptsDocType(docType) || !config.acceptsXPath(xpath)) {
            return DiffEligibility.NOT_APPLICABLE;
        }
        if (blob.getLength() > config.getMaxBlobSize()) {
            return DiffEligibility.TOO_LARGE;
        }
        if (findExtractor(blob.getMimeType()) == null) {
            return DiffEligibility.UNSUPPORTED_TYPE;
        }
        return DiffEligibility.ELIGIBLE;
    }

    /**
     * Returns the verdict for the pair, as {@link BlobDiffService#getEligibility} describes it.
     * <p>
     * Extractor instances are compared by identity, on one snapshot of the list: there is one instance
     * per contribution, and a concurrent {@link #start} replacing the list must not make two lookups
     * of the same contribution look different.
     */
    @Override
    public DiffEligibility getEligibility(String docType, String xpath, Blob oldBlob, Blob newBlob) {
        List<ResolvedExtractor> candidates = extractors;
        DiffEligibility eligibility = DiffEligibility.combine(getEligibility(docType, xpath, oldBlob),
                getEligibility(docType, xpath, newBlob));
        if (eligibility != DiffEligibility.ELIGIBLE) {
            return eligibility;
        }
        BlobContentExtractor oldExtractor = findExtractor(candidates, oldBlob.getMimeType());
        BlobContentExtractor newExtractor = findExtractor(candidates, newBlob.getMimeType());
        return oldExtractor == newExtractor ? DiffEligibility.ELIGIBLE : DiffEligibility.FORMAT_CHANGED;
    }

    protected BlobContentExtractor findExtractor(String mimeType) {
        return findExtractor(extractors, mimeType);
    }

    /**
     * Returns the image inventory extractor contributed for the mime type, or {@code null}.
     *
     * @since 2025.1
     */
    protected BlobContentExtractor findImageExtractor(String mimeType) {
        return findExtractor(imageExtractors, mimeType);
    }

    protected BlobContentExtractor findExtractor(List<ResolvedExtractor> candidates, String mimeType) {
        ResolvedExtractor resolved = findResolved(candidates, mimeType);
        return resolved == null ? null : resolved.extractor();
    }

    /**
     * Returns the first candidate, in order, whose contribution accepts the mime type, or
     * {@code null}.
     *
     * @since 2025.1
     */
    protected ResolvedExtractor findResolved(List<ResolvedExtractor> candidates, String mimeType) {
        if (candidates == null) {
            return null;
        }
        for (ResolvedExtractor resolved : candidates) {
            if (resolved.descriptor().accepts(mimeType)) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * Extracts the comparable content of the blob with the extractor contributed for its mime type.
     * <p>
     * {@code null} means <b>no extractor is registered</b> for the mime type, and nothing else.
     * <p>
     * A failing extractor raises instead. Reported the same way, it would make {@code BlobDiffWork}
     * file a real incident as {@code skippedUnsupportedType} - a status {@code BlobDiff.Retry}
     * refuses, which would make the failure permanent and invisible. Raising is what lets the work
     * record {@code error}, through its {@code catch (RuntimeException)}.
     *
     * @return the extracted content, or {@code null} if and only if no extractor handles the mime type
     * @throws NuxeoException if an extractor was found but could not read the binary
     */
    protected DiffableContent extract(Blob blob) {
        BlobContentExtractor extractor = findExtractor(blob.getMimeType());
        if (extractor == null) {
            return null;
        }
        try {
            return extractor.extract(blob, getConfig().getMaxLines());
        } catch (IOException e) {
            throw new NuxeoException("Blob content extraction failed for mime type " + blob.getMimeType(), e);
        }
    }

    /**
     * Compares the two blobs, each materialised locally <b>once</b>.
     * <p>
     * Every extraction pass then reads the local copy: the {@link MaterializedBlob#of(Blob)} each
     * extractor needing a file goes through is a pass-through. Without this, a pair of S3-backed
     * blobs would mean one download per pass: four for two files as soon as the image inventory is
     * on.
     */
    @Override
    public DiffResult diff(Blob oldBlob, Blob newBlob) {
        try (MaterializedBlob oldLocal = MaterializedBlob.of(oldBlob);
                MaterializedBlob newLocal = MaterializedBlob.of(newBlob)) {
            return diffLocal(oldLocal.blob(), newLocal.blob());
        }
    }

    protected DiffResult diffLocal(Blob oldBlob, Blob newBlob) {
        List<ResolvedExtractor> candidates = extractors;
        DiffableContent oldContent = extract(oldBlob);
        DiffableContent newContent = extract(newBlob);
        if (oldContent == null || newContent == null) {
            return null;
        }
        BlobDiffConfigDescriptor config = getConfig();
        var differ = new TextDiffer(config.getMaxDiffEntries(), config.getMaxDiffChars(),
                config.getMaxValueLength());
        DiffResult textResult = differ.diff(oldContent, newContent);
        if (isReplaced(candidates, oldBlob, newBlob, oldContent, newContent, textResult)) {
            textResult = textResult.asReplaced();
        }
        if (!config.isImageInventory()) {
            return textResult;
        }
        BlobContentExtractor imageExtractor = findImageExtractor(newBlob.getMimeType());
        if (imageExtractor == null) {
            return textResult;
        }
        try {
            int maxLines = config.getMaxLines();
            DiffResult imageResult = differ.diff(imageExtractor.extract(oldBlob, maxLines),
                    imageExtractor.extract(newBlob, maxLines));
            return differ.merge(textResult, imageResult);
        } catch (IOException | RuntimeException e) {
            // Deliberately asymmetric with extract(): a failing *text* extraction raises, because
            // there is nothing left to record, while a failing image inventory only costs a
            // section of the report. Do not "harmonize" the two.
            log.warn("Image inventory extraction failed for mime type {}", newBlob.getMimeType(), e);
            return textResult;
        }
    }

    /**
     * Returns whether the pair changed mime type and shares too little to be worth a diff.
     * <p>
     * Only a pair read by one contribution reaches this point - two contributions are
     * {@code FORMAT_CHANGED} - so the rule of the new side's contribution is the rule of both. A
     * rewrite within one mime type is always diffed: the rule is about a file replaced by another
     * one, not about how much an author changed.
     */
    protected boolean isReplaced(List<ResolvedExtractor> candidates, Blob oldBlob, Blob newBlob,
            DiffableContent oldContent, DiffableContent newContent, DiffResult result) {
        if (result.similarity() == null) {
            return false;
        }
        if (Objects.equals(ExtractorDescriptor.normalizeMimeType(oldBlob.getMimeType()),
                ExtractorDescriptor.normalizeMimeType(newBlob.getMimeType()))) {
            return false;
        }
        ResolvedExtractor resolved = findResolved(candidates, newBlob.getMimeType());
        if (resolved == null || resolved.minSimilarity() <= 0) {
            return false;
        }
        if (Math.min(oldContent.size(), newContent.size()) < resolved.minSimilarityLines()) {
            return false;
        }
        return result.similarity() < resolved.minSimilarity();
    }
}
