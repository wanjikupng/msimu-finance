package com.tracom.lipamdogo.dto;

public record ProviderFeedImportResponse(
        int received,
        int inserted,
        int skippedDuplicates
) {
}
