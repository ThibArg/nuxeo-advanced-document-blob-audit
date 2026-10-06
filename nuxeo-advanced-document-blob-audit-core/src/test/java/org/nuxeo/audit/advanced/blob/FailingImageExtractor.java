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

import java.io.IOException;

import org.nuxeo.ecm.core.api.Blob;

/**
 * Image inventory extractor that always fails, contributed by
 * {@code blobaudit-test-failingimage-contrib.xml}.
 * <p>
 * There is no dependable way to build a real binary that the text converter reads happily and the
 * image inventory chokes on, so the failure is injected instead. What the fixture is there to prove
 * is the asymmetry introduced by COR-01: a failing <i>text</i> extraction raises, a failing image
 * inventory only costs the image section of the report.
 *
 * @since 2025.1
 */
public class FailingImageExtractor implements BlobTextExtractor {

    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        throw new IOException("image inventory deliberately failing on " + blob.getFilename());
    }
}
