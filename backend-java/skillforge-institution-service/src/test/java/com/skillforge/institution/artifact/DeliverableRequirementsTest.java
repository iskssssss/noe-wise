package com.skillforge.institution.artifact;

import static com.skillforge.institution.artifact.DeliverableContract.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 只核验已选真实成果的清单结构，不从自然语言推导文件义务。 */
class DeliverableRequirementsTest {
    private final DeliverableRequirements requirements = new DeliverableRequirements();

    @Test void selectedRepresentationsAloneDefineTheFrozenManifest() {
        var selected = new Selection("report", "报告", "document", "current-revision",
                List.of(representation("source", "md", true, null, "report.md", List.of()),
                        representation("export", "pdf", false, null, "report.pdf", List.of(new FileRef("source", "report.md")))));
        var planned = requirements.verifyForFinalization(List.of(selected));
        assertEquals(List.of(new Planned("report", "报告", "document", "current-revision",
                List.of(new Format("md"), new Format("pdf")))), planned);
    }

    @Test void zipSelectionDoesNotRequireANaturalLanguageClassifierReceipt() {
        var selected = new Selection("bundle", "交付包", "document", null,
                List.of(representation("bundle", "zip", true, null, "bundle.zip", List.of())));
        assertEquals(List.of(new Format("zip")), requirements.verifyForFinalization(List.of(selected)).getFirst().formats());
    }

    @Test void aManifestMustHaveRepresentationsAndExactlyOnePrimaryPreview() {
        for (var representations : List.of(List.<Representation>of(),
                List.of(representation("source", "md", false, null, "report.md", List.of())),
                List.of(representation("source", "md", true, null, "report.md", List.of()),
                        representation("source", "txt", true, null, "report.txt", List.of())))) {
            var rejected = assertThrows(OutputContractRejection.class,
                    () -> DeliverableRequirements.validateSelectionShape(List.of(selection(representations))));
            assertEquals("OUTPUT_MANIFEST_INVALID", rejected.feedback().get("error"));
        }
    }

    @Test void formatsMustBeCanonicalUniqueAndUseTheCorrectRepresentationRole() {
        for (var representations : List.of(
                List.of(representation("source", "PDF", true, null, "report.pdf", List.of())),
                List.of(representation("source", "md", true, null, "one.md", List.of()),
                        representation("source", "md", false, null, "two.md", List.of())),
                List.of(representation("source", "zip", true, null, "bundle.zip", List.of())),
                List.of(representation("bundle", "html", true, "index.html", "index.html", List.of())))) {
            assertThrows(OutputContractRejection.class,
                    () -> DeliverableRequirements.validateSelectionShape(List.of(selection(representations))));
        }
    }

    @Test void filesAndExportSourcesRequireActualReferenceShapes() {
        for (var representation : List.of(
                new Representation("source", "md", true, null, List.of(), List.of()),
                new Representation("source", "md", true, null, List.of(new FileRef("", "report.md")), List.of()),
                new Representation("source", "md", true, null, List.of(new FileRef(null, null, "")), List.of()),
                representation("export", "pdf", true, null, "report.pdf", List.of()),
                representation("export", "pdf", true, null, "report.pdf", List.of(new FileRef("source", ""))))) {
            assertThrows(OutputContractRejection.class,
                    () -> DeliverableRequirements.validateSelectionShape(List.of(selection(List.of(representation)))));
        }
    }

    @Test void htmlEntryMustNameASelectedFileButMultipleLocalResourcesRemainValid() {
        var website = new Representation("source", "html", true, "index.html",
                List.of(new FileRef("website", "index.html"), new FileRef("website", "style.css")), List.of());
        assertDoesNotThrow(() -> DeliverableRequirements.validateSelectionShape(List.of(selection(List.of(website)))));
        for (String entry : List.of("", "missing.html")) {
            var invalid = new Representation("source", "html", true, entry, website.files(), List.of());
            assertThrows(OutputContractRejection.class,
                    () -> DeliverableRequirements.validateSelectionShape(List.of(selection(List.of(invalid)))));
        }
    }

    private static Selection selection(List<Representation> representations) {
        return new Selection("report", "报告", "document", null, representations);
    }

    private static Representation representation(String role, String format, boolean preview, String entry,
            String path, List<FileRef> sources) {
        return new Representation(role, format, preview, entry, List.of(new FileRef("actual-artifact", path)), sources);
    }
}
