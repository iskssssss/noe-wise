package com.skillforge.runtime.adapter.model.transport;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SdkHttpResourcesTest {
    @Test void closingAssemblyShutsDownThePoolAndRejectsNewBorrowers() {
        var resources = new SdkHttpResources();
        var client = resources.client();
        resources.close();
        resources.close();
        assertTrue(client.dispatcher().executorService().isShutdown());
        assertEquals(0, client.connectionPool().connectionCount());
        assertThrows(IllegalStateException.class, resources::client);
    }
}
