package com.skillforge.institution.channel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channels/tokens/rotate-with-receipt")
public class ChannelTokenReceiptController {
    private final ChannelTokenReceiptService service;
    public ChannelTokenReceiptController(ChannelTokenReceiptService service) { this.service = service; }

    @PostMapping
    public ApiResponse<ChannelTokenReceiptService.Result> rotate(@Valid @RequestBody Command command) {
        return ApiResponses.wrap(service.rotate(Long.parseLong(TenantIds.requireTenantId()), command.requestId(), command.channelId()));
    }

    @GetMapping("/result")
    public ResponseEntity<ApiResponse<ChannelTokenReceiptService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }

    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
            @JsonProperty("channel_id") @NotNull @Positive Long channelId) { }
}
