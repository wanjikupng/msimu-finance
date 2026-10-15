package com.tracom.lipamdogo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ProviderFeedImportRequest(
        @NotEmpty(message = "At least one provider transaction is required.")
        List<@Valid ProviderFeedItem> transactions
) {
}
