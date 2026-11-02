package com.skillforge.institution.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证实际 Servlet multipart 解析阶段的拒绝，不连接数据库或供应商。 */
@SpringBootTest(classes = KnowledgeUploadLimitHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.servlet.multipart.max-file-size=128B", "spring.servlet.multipart.max-request-size=2KB",
                "spring.autoconfigure.exclude=com.skillforge.runtime.starter.AgentRuntimeAutoConfiguration"})
class KnowledgeUploadLimitHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired Probe probe;

    @Test
    void oversizedFileIsRejectedBeforeController() {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(new byte[256]) {
            @Override public String getFilename() { return "large.txt"; }
        });
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/v1/knowledge/bases/documents/upload", HttpMethod.POST,
                new HttpEntity<>(parts, headers), String.class);
        assertEquals(413, response.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertTrue(response.getBody().contains("KNOWLEDGE_UPLOAD_REJECTED"));
        assertEquals(0, probe.calls.get());
        parts.set("file", new ByteArrayResource(new byte[8]) {
            @Override public String getFilename() { return "small.txt"; }
        });
        var accepted = http.exchange("/api/v1/knowledge/bases/documents/upload", HttpMethod.POST,
                new HttpEntity<>(parts, headers), String.class);
        assertEquals(200, accepted.getStatusCode().value());
        assertEquals(1, probe.calls.get());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class Application {
        @Bean GlobalExceptionHandler errors(ObjectMapper json) { return new GlobalExceptionHandler(json); }
        @Bean Probe probe() { return new Probe(); }
    }

    @RestController
    static class Probe {
        final AtomicInteger calls = new AtomicInteger();
        @PostMapping("/api/v1/knowledge/bases/documents/upload")
        String upload(@org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
            calls.incrementAndGet();
            return "accepted";
        }
    }
}
