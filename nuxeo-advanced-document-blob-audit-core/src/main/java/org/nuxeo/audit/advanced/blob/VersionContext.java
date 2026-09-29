/* (C) Copyright 2026 Nuxeo SA and others. Licensed under Apache License 2.0. */
package org.nuxeo.audit.advanced.blob;

import java.io.Serializable;

/** Identity of the two versions compared by a BlobDiff. */
public record VersionContext(String previousVersionId, String previousVersionLabel,
        String newVersionId, String newVersionLabel, String versionSeriesId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final VersionContext NONE = new VersionContext(null, null, null, null, null);
}
