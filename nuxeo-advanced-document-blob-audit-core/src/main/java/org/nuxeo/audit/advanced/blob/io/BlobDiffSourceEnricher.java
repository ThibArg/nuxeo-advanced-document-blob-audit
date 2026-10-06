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
package org.nuxeo.audit.advanced.blob.io;

import static org.nuxeo.ecm.core.io.registry.reflect.Instantiations.SINGLETON;
import static org.nuxeo.ecm.core.io.registry.reflect.Priorities.REFERENCE;

import java.io.IOException;

import org.nuxeo.audit.advanced.blob.BlobAuditConstants;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.io.marshallers.json.enrichers.AbstractJsonEnricher;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext.SessionWrapper;
import org.nuxeo.ecm.core.io.registry.reflect.Setup;

import com.fasterxml.jackson.core.JsonGenerator;

/**
 * {@code blobDiffSource} enricher for {@code BlobDiff} documents: tells the UI whether the source
 * document still exists, is in the trash, and whether the current user can open it.
 * <p>
 * Output: {@code {"exists", "trashed", "readable", "uid", "title", "path", "type"}}. Title, path
 * and type are only written when the current user can read the source. Existence and trash state
 * are computed with a privileged session: the reader of a BlobDiff is an auditor, who is entitled to
 * know that a source was deleted.
 * <p>
 * A {@code documentResolver} on {@code bdiff:sourceId} was deliberately not used: reference
 * validation would forbid recording an error diff for a source that no longer exists.
 *
 * @since 2025.1
 */
@Setup(mode = SINGLETON, priority = REFERENCE)
public class BlobDiffSourceEnricher extends AbstractJsonEnricher<DocumentModel> {

    public static final String NAME = "blobDiffSource";

    public BlobDiffSourceEnricher() {
        super(NAME);
    }

    @Override
    public void write(JsonGenerator jg, DocumentModel document) throws IOException {
        jg.writeFieldName(NAME);
        Object sourceId = BlobAuditConstants.DIFF_DOCTYPE.equals(document.getType())
                ? document.getPropertyValue(BlobAuditConstants.XP_SOURCE_ID)
                : null;
        if (sourceId == null) {
            jg.writeNull();
            return;
        }
        IdRef ref = new IdRef(sourceId.toString());
        try (SessionWrapper wrapper = ctx.getSession(document)) {
            CoreSession session = wrapper.getSession();
            boolean[] state = CoreInstance.doPrivileged(session, s -> {
                boolean exists = s.exists(ref);
                return new boolean[] { exists, exists && s.getDocument(ref).isTrashed() };
            });
            boolean readable = state[0] && session.hasPermission(ref, SecurityConstants.READ);
            jg.writeStartObject();
            jg.writeStringField("uid", ref.toString());
            jg.writeBooleanField("exists", state[0]);
            jg.writeBooleanField("trashed", state[1]);
            jg.writeBooleanField("readable", readable);
            if (readable) {
                DocumentModel source = session.getDocument(ref);
                jg.writeStringField("title", source.getTitle());
                jg.writeStringField("path", source.getPathAsString());
                jg.writeStringField("type", source.getType());
            }
            jg.writeEndObject();
        }
    }
}
