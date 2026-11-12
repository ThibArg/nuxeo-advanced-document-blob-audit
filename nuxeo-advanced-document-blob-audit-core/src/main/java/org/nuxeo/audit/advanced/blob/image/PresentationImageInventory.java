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
package org.nuxeo.audit.advanced.blob.image;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.extractor.SlideHandler;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.xml.sax.SAXException;

/**
 * Image inventory of PowerPoint OOXML files: one entry per picture placed on a slide, keyed by
 * {@code powerpoint:slide:<n>:image:<media-part>}. The same media part used twice on one slide gets
 * a {@code #2}, {@code #3}... suffix so no placement is lost. Pictures inside group shapes are
 * included; layouts and masters are not (they belong to the template, not the content).
 *
 * @since 2025.1
 */
public class PresentationImageInventory extends AbstractImageInventory {

    /**
     * Slides are streamed one by one with SAX: {@code XMLSlideShow} would parse the whole deck into
     * XMLBeans trees before the first picture is read.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        Inventory inventory = new Inventory(maxLines, new ImageBudget(new HashMap<>()));
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            OPCPackage pkg = OoxmlPackages.open(local.file());
            try {
                int slideNumber = 0;
                for (PackagePart slide : SlideHandler.slides(pkg)) {
                    slideNumber++;
                    OoxmlPackages.parse(slide,
                            new SlidePictures(slide, "powerpoint:slide:" + slideNumber + ":image:", inventory));
                }
            } catch (OoxmlPackages.Stop e) {
                inventory.truncate();
            } catch (SAXException e) {
                throw OoxmlPackages.unreadable(e, "OOXML presentation: " + blob.getFilename());
            } finally {
                pkg.revert();
            }
        }
        return inventory.result();
    }

    protected class SlidePictures extends SlideHandler {

        protected final PackagePart slide;

        protected final String prefix;

        protected final Inventory inventory;

        protected final Map<String, Integer> seen = new HashMap<>();

        protected SlidePictures(PackagePart slide, String prefix, Inventory inventory) {
            this.slide = slide;
            this.prefix = prefix;
            this.inventory = inventory;
        }

        @Override
        protected boolean readsText(boolean topLevel, String placeholder) {
            return false;
        }

        @Override
        protected boolean readsTables() {
            return false;
        }

        @Override
        protected void picture(String relationshipId) throws SAXException {
            PackagePart media = OoxmlPackages.related(slide, relationshipId);
            if (media == null) {
                return; // linked (external) picture: no embedded bytes to digest
            }
            if (inventory.isFull()) {
                throw new OoxmlPackages.Stop();
            }
            String name = media.getPartName().getName();
            inventory(media, occurrence(seen, prefix + name.substring(name.lastIndexOf('/') + 1)), inventory);
        }
    }
}
