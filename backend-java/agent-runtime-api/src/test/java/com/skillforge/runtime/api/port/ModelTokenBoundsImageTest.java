package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelTokenBoundsImageTest {
    private static final ModelImagePolicy POLICY = new ModelImagePolicy(true, true, 4, 1024,
            8192, 2048, 512, 85, 170, "vision.v1");
    private static final MediaRef MEDIA = new MediaRef(new SessionRef(new TenantId("tenant"), "session"),
            "image", "0".repeat(64), "image/png", 100, 513, 512);

    @Test void pureImageReservesFrozenTileCostWithoutCountingReferenceAsText() {
        var image = new ModelContent.Image(MEDIA, ImageDetail.HIGH);
        var message = new ModelPort.ModelMessage("input", ModelPort.ModelRole.USER, List.of(image));
        assertEquals(425, ModelTokenBounds.imageTokens(image, POLICY));
        assertEquals(745, ModelTokenBounds.input(List.of(message), List.of(), POLICY));
        assertThrows(IllegalArgumentException.class, () -> ModelTokenBounds.input(List.of(message), List.of()));
    }

    @Test void toolImageReservesBridgeTextAndAdditionalMessageFraming() {
        var text = new ModelContent.Text("result");
        var image = new ModelContent.Image(MEDIA, ImageDetail.LOW);
        var plain = new ModelPort.ModelMessage("result", ModelPort.ModelRole.TOOL, List.of(text),
                List.of(), Optional.of("call-1"), Optional.empty());
        var pictured = new ModelPort.ModelMessage("result", ModelPort.ModelRole.TOOL, List.of(text, image),
                List.of(), Optional.of("call-1"), Optional.empty());
        assertEquals(85 + 320 + "result".length() + "call-1".length(),
                ModelTokenBounds.input(List.of(pictured), List.of(), POLICY) - ModelTokenBounds.input(List.of(plain), List.of(), POLICY));
    }
}
