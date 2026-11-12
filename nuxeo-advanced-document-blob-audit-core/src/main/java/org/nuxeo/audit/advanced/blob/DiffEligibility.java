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

/**
 * Why a given document/xpath/blob can - or cannot - be content-diffed.
 * <p>
 * The distinction matters beyond a yes/no answer: a binary that really changed but
 * cannot be diffed must still leave an audit entry, otherwise the absence of an entry is
 * indistinguishable from the absence of a change. Only {@link #NOT_APPLICABLE} stays fully silent,
 * because it means the feature was never meant to look at that property in the first place.
 *
 * @since 2025.1
 */
public enum DiffEligibility {

    /** The blob can be extracted and compared. */
    ELIGIBLE(null),

    /** The blob is bigger than {@code maxBlobSize}: report the change, do not diff it. */
    TOO_LARGE(DiffStatus.SKIPPED_TOO_LARGE),

    /** No contributed extractor handles the mime type: report the change, do not diff it. */
    UNSUPPORTED_TYPE(DiffStatus.SKIPPED_UNSUPPORTED_TYPE),

    /**
     * Both sides are supported, but by different extractor contributions: report the change, do not
     * diff it. A <b>pair-level</b> verdict, returned by the two-blob variant of
     * {@code BlobDiffService#getEligibility} only, never by the evaluation of a single blob.
     *
     * @since 2025.1
     */
    FORMAT_CHANGED(DiffStatus.SKIPPED_FORMAT_CHANGED),

    /**
     * Out of scope entirely - feature disabled, document type or xpath not covered, no blob. Stays
     * silent: nothing is audited, nothing is scheduled.
     */
    NOT_APPLICABLE(null);

    /** The skip to report, {@code null} for {@link #ELIGIBLE} and {@link #NOT_APPLICABLE}. */
    protected final DiffStatus status;

    DiffEligibility(DiffStatus status) {
        this.status = status;
    }

    /**
     * The {@code skipReason} to record in the audit entry, or {@code null} when nothing must be
     * reported.
     */
    public String getSkipReason() {
        return status == null ? null : status.value();
    }

    /** {@code true} when the binary change must be audited even though it cannot be diffed. */
    public boolean isReportable() {
        return status != null;
    }

    /**
     * Combines the verdicts of the two sides of a comparison.
     * <p>
     * Precedence is {@code NOT_APPLICABLE} > {@code TOO_LARGE} > {@code UNSUPPORTED_TYPE} >
     * {@code FORMAT_CHANGED} > {@code ELIGIBLE}. {@code NOT_APPLICABLE} wins because it does not
     * depend on the blob at all - it means the feature is off or the property is out of scope - and
     * must never be turned into a reported skip by the state of the other side.
     * {@code FORMAT_CHANGED} comes last: it only means something once both sides are known to be
     * supported.
     */
    public static DiffEligibility combine(DiffEligibility left, DiffEligibility right) {
        if (left == NOT_APPLICABLE || right == NOT_APPLICABLE) {
            return NOT_APPLICABLE;
        }
        if (left == TOO_LARGE || right == TOO_LARGE) {
            return TOO_LARGE;
        }
        if (left == UNSUPPORTED_TYPE || right == UNSUPPORTED_TYPE) {
            return UNSUPPORTED_TYPE;
        }
        if (left == FORMAT_CHANGED || right == FORMAT_CHANGED) {
            return FORMAT_CHANGED;
        }
        return ELIGIBLE;
    }
}
