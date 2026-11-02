package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 真实会话授权、HTTP 和隔离 PostgreSQL 的图片上传回执；不调用模型。 */
class MediaUploadReceiptHttpIntegrationTest extends ChatExecutionChainE2ETest {
    @Test void originalUploadCanBeReadWithoutRepeatingTheUpload() throws Exception {
        login();
        String upload = UUID.randomUUID().toString();
        String receiptPath = "/api/v1/chat/sessions/media/upload-receipt?session_id=" + sessionId + "&upload_id=" + upload;
        assertEquals("UNKNOWN", successData(send(receiptPath, "GET", null, accessToken)).path("status").asText());
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        String boundary = "receipt-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"session_id\"\r\n\r\n" + sessionId
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"upload_id\"\r\n\r\n" + upload
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes.toByteArray());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/sessions/media/upload"))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var uploaded = successData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        var receipt = successData(send(receiptPath, "GET", null, accessToken));
        assertEquals("FOUND", receipt.path("status").asText());
        assertEquals(uploaded.path("media_id").asText(), receipt.path("media").path("media_id").asText());
        String other = successData(send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken)).path("session_id").asText();
        assertEquals("UNKNOWN", successData(send(receiptPath.replace("session_id=" + sessionId, "session_id=" + other), "GET", null, accessToken)).path("status").asText());
    }
}
