package com.skillforge.institution.principal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/service-principals/issue-with-receipt")
public class PrincipalIssueReceiptController {
    private final PrincipalIssueReceiptService service;
    public PrincipalIssueReceiptController(PrincipalIssueReceiptService service) { this.service = service; }

    @PostMapping
    public ApiResponse<PrincipalIssueReceiptService.Result> issue(@Valid @RequestBody Command command) {
        return ApiResponses.wrap(service.issue(Long.parseLong(TenantIds.requireTenantId()), command.requestId(), command.integrationId(), command.scopes()));
    }

    @GetMapping("/result")
    public ResponseEntity<ApiResponse<PrincipalIssueReceiptService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }

    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
            @JsonProperty("integration_id") @NotBlank String integrationId,
            @JsonProperty("scopes") @NotEmpty List<@NotBlank String> scopes) { }
}
