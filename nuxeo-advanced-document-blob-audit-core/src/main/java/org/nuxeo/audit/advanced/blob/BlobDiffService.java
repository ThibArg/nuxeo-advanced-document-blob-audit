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

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Entry point of the blob diff feature. Persistence of the results is {@link BlobDiffStore}.
 *
 * @since 2025.1
 */
public interface BlobDiffService {

    /** Returns the effective configuration, every contribution merged. */
    BlobDiffConfigDescriptor getConfig();

    /**
     * Returns whether the change from {@code oldBlob} to {@code newBlob} can be content-diffed, and
     * if not, why.
     * <p>
     * Combines the verdict of each side, then returns {@link DiffEligibility#FORMAT_CHANGED} when
     * both are eligible but resolve to different extractor contributions. Each contribution is a
     * comparability class: two blobs read by the same one are cut into lines the same way, and two
     * blobs read by different ones are not, so their diff would report everything as removed and
     * added. The check-in path and {@code BlobDiffWork} both call this, so they cannot drift apart.
     *
     * @since 2025.1
     */
    DiffEligibility getEligibility(String docType, String xpath, Blob oldBlob, Blob newBlob);

    /**
     * Compares two blobs. Both blobs must be non-null.
     * <p>
     * The result always holds the full body. When the pair changed mime type and its similarity is
     * under the {@code minSimilarity} of its extractor contribution, it is flagged
     * {@link DiffResult#replaced()}: {@code BlobDiffWork} then stores the counters without the body.
     * Calling this on the blobs of two versions is how an integrator recomputes such a pair.
     *
     * @return the result, or {@code null} if either side has no registered extractor
     * @throws NuxeoException if an extraction failed
     */
    DiffResult diff(Blob oldBlob, Blob newBlob);
}
