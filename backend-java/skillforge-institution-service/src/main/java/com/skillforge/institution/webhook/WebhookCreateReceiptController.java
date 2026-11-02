package com.skillforge.institution.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhook-subscriptions/create-with-receipt")
public class WebhookCreateReceiptController {
    private final WebhookCreateReceiptService service;
    public WebhookCreateReceiptController(WebhookCreateReceiptService service) { this.service = service; }

    @PostMapping
    public ApiResponse<WebhookCreateReceiptService.Result> create(@Valid @RequestBody Command command) {
        return ApiResponses.wrap(service.create(Long.parseLong(TenantIds.requireTenantId()), command.requestId(),
                command.sessionId(), command.endpointUrl(), command.signingSecret()));
    }

    @GetMapping("/result")
    public ResponseEntity<ApiResponse<WebhookCreateReceiptService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }

    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("endpoint_url") @NotBlank @Size(max = 2048) String endpointUrl,
            @JsonProperty("signing_secret") @NotBlank @Size(max = 4096) String signingSecret) { }
}
