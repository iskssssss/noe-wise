package com.skillforge.institution.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeUploadLimitTest {
    @Test
    void oversizedKnowledgeUploadHasAnExplicitPreDispatchRejection() {
        var request = new MockHttpServletRequest("POST", "/api/v1/knowledge/bases/documents/upload");
        var result = new GlobalExceptionHandler(new ObjectMapper()).uploadTooLarge(
                new MaxUploadSizeExceededException(10), request, new MockHttpServletResponse());
        assertEquals(413, result.getStatusCode().value());
        assertEquals("KNOWLEDGE_UPLOAD_REJECTED", result.getBody().code());
    }

    @Test
    void otherMultipartEndpointsKeepTheirOwnErrorContract() {
        var request = new MockHttpServletRequest("POST", "/api/v1/other/upload");
        var result = new GlobalExceptionHandler(new ObjectMapper()).uploadTooLarge(
                new MaxUploadSizeExceededException(10), request, new MockHttpServletResponse());
        assertEquals(413, result.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", result.getBody().code());
    }
}
