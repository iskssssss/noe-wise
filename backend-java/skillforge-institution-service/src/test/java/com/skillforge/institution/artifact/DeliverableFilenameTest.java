package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeliverableFilenameTest {
    @Test
    void normalizesExistingFilenameWithoutDerivingItFromBusinessTitle() {
        assertEquals("ABC 报告.pdf", DeliverableFilename.validateFilename("ＡＢＣ 报告.pdf"));
        assertEquals("decision.md",DeliverableFilename.validateFilename("decision.md"));
        assertEquals("data.csv",DeliverableFilename.validateFilename("data.csv"));
        assertEquals("index.html",DeliverableFilename.validateFilename("index.html"));
        assertEquals("pdf",DeliverableFilename.validateFormat("PDF"));
        assertThrows(IllegalArgumentException.class,()->DeliverableFilename.validateFormat("../pdf"));
    }

    @Test
    void rejectsPathControlReservedAndOverlongNames() {
        assertThrows(IllegalArgumentException.class, () -> DeliverableFilename.validateFilename("../报告.pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeliverableFilename.validateFilename("报告\n副本.pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeliverableFilename.validateFilename("CON.pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeliverableFilename.validateFilename("CON .pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeliverableFilename.validateFilename("a".repeat(197)+".pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeliverableFilename.validateFilename("报".repeat(84)+".txt"));
    }

    @Test
    void deterministicallyDisambiguatesAndVersionsNames() {
        assertEquals("报告_2.pdf", DeliverableFilename.deduplicate("报告.pdf", List.of("报告.pdf", "报告_1.pdf")));
        assertEquals("报告-v3.pdf", DeliverableFilename.legacyVersion("报告.pdf", 3));
    }
}
