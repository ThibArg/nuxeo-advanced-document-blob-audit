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

import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.CoreSearchFeature;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.ecm.platform.query.api.PageProviderSpec;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * {@code BLOB_DIFFS_ADMIN}, the query behind the audit search, its results and the document tab.
 * <p>
 * BLD-04 (code review 2026-10): it had no test, although its predicates were already dropped in
 * silence once (item 25: an inert {@code whereClause@docType} made every filter a no-op). It needs
 * a search service, not a search engine: {@link CoreSearchFeature} runs it on the repository search
 * client, as a bare instance does. Aggregates are left out, that client has no {@code AGGREGATE}
 * capability.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features({ BlobAuditFeature.class, CoreSearchFeature.class })
public class TestBlobDiffAdminSearch {

    protected static final String PROVIDER = "BLOB_DIFFS_ADMIN";

    @Inject
    protected CoreSession session;

    @Inject
    protected BlobDiffService blobDiffService;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    protected String first;

    protected String second;

    protected String third;

    @Before
    public void createDiffs() {
        first = diff("src-a", "alice", date(Calendar.JANUARY, 10), false);
        second = diff("src-a", "bob", date(Calendar.MARCH, 10), true);
        third = diff("src-b", "alice", date(Calendar.MARCH, 12), false);
        txFeature.nextTransaction();
    }

    protected String diff(String sourceId, String user, Date date, boolean truncated) {
        DiffResult result = new DiffResult(1, 0, 0, truncated, "+ line\n");
        DocumentModel diff = blobDiffService.createDiffDocument(session, sourceId, session.getRepositoryName(),
                sourceId, "file:content", null, null, FrozenBlobs.NONE, user, date, result,
                BlobAuditConstants.STATUS_OK, UUID.randomUUID().toString());
        session.save();
        return diff.getId();
    }

    protected Date date(int month, int day) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2026, month, day, 10, 0, 0);
        return calendar.getTime();
    }

    /**
     * Ids of the diffs matching the given named parameters, built into the search document the way
     * the REST search endpoint does it for Web UI: string values, {@code bdsearch:} keys.
     */
    @SuppressWarnings("unchecked")
    protected TreeSet<String> search(Map<String, String> namedParameters) {
        DocumentModel searchDocument = PageProviderHelper.getSearchDocumentModel(session, PROVIDER,
                namedParameters);
        PageProviderSpec spec = PageProviderSpec.builder(PROVIDER)
                                                .searchDocument(searchDocument)
                                                .property("coreSession", (Serializable) session)
                                                .build();
        TreeSet<String> ids = new TreeSet<>();
        for (DocumentModel doc : (List<DocumentModel>) pageProviderService.getPageProvider(spec)
                                                                          .getCurrentPage()) {
            ids.add(doc.getId());
        }
        return ids;
    }

    protected TreeSet<String> ids(String... ids) {
        return new TreeSet<>(List.of(ids));
    }

    @Test
    public void testWithoutFilterEveryDiffIsListed() {
        assertEquals(ids(first, second, third), search(Map.of()));
    }

    @Test
    public void testTheSourceFilterNarrows() {
        assertEquals(ids(first, second), search(Map.of("bdsearch:sourceId", "src-a")));
    }

    @Test
    public void testTheUserFilterNarrows() {
        assertEquals(ids(first, third), search(Map.of("bdsearch:user", "alice")));
    }

    @Test
    public void testTheDateRangeNarrows() {
        assertEquals(ids(second, third),
                search(Map.of("bdsearch:date_min", "2026-03-01", "bdsearch:date_max", "2026-03-31")));
    }

    @Test
    public void testTheTruncatedFilterNarrows() {
        assertEquals(ids(second), search(Map.of("bdsearch:truncated", "true")));
    }

    @Test
    public void testFiltersCombine() {
        assertEquals(ids(first), search(Map.of("bdsearch:sourceId", "src-a", "bdsearch:user", "alice")));
    }
}
