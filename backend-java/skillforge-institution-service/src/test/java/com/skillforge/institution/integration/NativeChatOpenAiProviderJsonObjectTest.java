package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 只证明本地HTTP夹具的JSON_OBJECT正文，不代表外部供应商或Memory业务能力。 */
class NativeChatOpenAiProviderJsonObjectTest {
    @Test
    void returnsActualJsonObjectContentForStreamingAndOrdinaryRequests()throws Exception {
        var json=new ObjectMapper();var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try(var provider=new NativeChatOpenAiProvider(json,"json-object-protocol")) {
            provider.start();
            for(boolean stream:List.of(false,true)) {
                String body=json.writeValueAsString(Map.of("model","fixture","stream",stream,
                        "response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","user","content","Return one JSON object"))));
                var response=http.send(HttpRequest.newBuilder(URI.create(provider.endpoint()+"/chat/completions"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode message;
                if(stream) {
                    var chunks=response.body().lines().filter(line->line.startsWith("data: ")&&!line.equals("data: [DONE]"))
                            .map(line->line.substring(6)).toList();
                    assertThat(chunks).hasSize(2);
                    message=json.readTree(chunks.getFirst()).path("choices").get(0).path("delta");
                    assertThat(json.readTree(chunks.getLast()).path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
                } else {
                    var choice=json.readTree(response.body()).path("choices").get(0);message=choice.path("message");
                    assertThat(choice.path("finish_reason").asText()).isEqualTo("stop");
                }
                assertThat(message.has("tool_calls")).isFalse();assertThat(message.path("content").isTextual()).isTrue();
                JsonNode decision=json.readTree(message.path("content").asText());
                assertThat(decision.isObject()).isTrue();assertThat(decision.path("type").asText()).isEqualTo("respond");
                assertThat(decision.path("output").asText()).isEqualTo("fixture-answer-json-object-protocol");
                assertThat(decision.path("evidence_item_ids").isArray()).isTrue();
            }
            assertThat(provider.requestCount()).isEqualTo(2);
            assertThat(provider.responseKinds()).containsExactly("json_object_respond","json_object_respond");
        }
    }
}
