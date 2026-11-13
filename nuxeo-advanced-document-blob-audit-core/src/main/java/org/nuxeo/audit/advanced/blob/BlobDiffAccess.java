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

import java.util.List;

import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.services.config.ConfigurationService;

/**
 * Who may manage diffs: platform administrators, members of the {@code administrators} group and
 * members of the configured auditors group.
 * <p>
 * This is an <b>additional</b> check for operations that do more than what the ACL of
 * {@code /change-diff} already enforces (scheduling a work, bulk purge). Reading and deleting are
 * enforced by the ACL itself.
 *
 * @since 2025.1
 */
public final class BlobDiffAccess {

    private BlobDiffAccess() {
        // utility class
    }

    /**
     * Returns whether the principal may manage diffs: an administrator, or a member of the
     * {@code administrators} group or of the auditors group.
     */
    public static boolean isAuditor(NuxeoPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.isAdministrator()) {
            return true;
        }
        List<String> groups = principal.getAllGroups();
        if (groups == null) {
            return false;
        }
        return groups.contains(BlobAuditConstants.DEFAULT_AUDITORS_GROUP) || groups.contains(auditorsGroup());
    }

    /**
     * Refuses a principal that may not manage diffs.
     *
     * @throws DocumentSecurityException if {@link #isAuditor(NuxeoPrincipal)} is {@code false}
     */
    public static void checkAuditor(NuxeoPrincipal principal) {
        if (!isAuditor(principal)) {
            throw new DocumentSecurityException(
                    "Only administrators and members of the auditors group can manage content diffs");
        }
    }

    /**
     * Returns the auditors group: the configuration property
     * {@value BlobAuditConstants#AUDITORS_GROUP_PROPERTY}, also read by Web UI as
     * {@code Nuxeo.UI.config.blobaudit.auditorsGroup}, or {@code administrators} when it is not set.
     *
     * @since 2025.1
     */
    public static String auditorsGroup() {
        String property = null;
        if (Framework.isInitialized()) {
            var configuration = Framework.getService(ConfigurationService.class);
            if (configuration != null) {
                property = configuration.getString(BlobAuditConstants.AUDITORS_GROUP_PROPERTY).orElse(null);
            }
        }
        return property == null || property.isBlank() ? BlobAuditConstants.DEFAULT_AUDITORS_GROUP : property.trim();
    }
}
