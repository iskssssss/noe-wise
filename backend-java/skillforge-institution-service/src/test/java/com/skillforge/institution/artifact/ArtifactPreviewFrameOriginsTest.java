package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ArtifactPreviewFrameOriginsTest {
    @Test void permitsOnlyConfiguredOriginsAndRetainsAdmin(){
        assertEquals("http://localhost:3000 http://localhost:3200 http://127.0.0.1:3200",
                ArtifactPreviewServer.frameAncestors("http://localhost:3000","http://localhost:3200,http://127.0.0.1:3200,http://localhost:3200"));
        assertEquals("https://admin.example.test",ArtifactPreviewServer.frameAncestors("https://admin.example.test",""));
        assertEquals("'none'",ArtifactPreviewServer.frameAncestors("",""));
    }
    @Test void rejectsWildcardsPathsAndCspInjection(){
        for(String invalid:new String[]{"*","https://*.example.test","https://chat.example.test/path","https://chat.example.test; script-src *","data:text/html,hello"})
            assertThrows(RuntimeException.class,()->ArtifactPreviewServer.frameAncestors("https://admin.example.test",invalid));
    }
}
