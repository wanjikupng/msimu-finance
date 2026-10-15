package com.tracom.lipamdogo.payment;

public record ProviderResult(
        ProviderOutcome outcome,
        String providerReference,
        String failureReason
) {
    public static ProviderResult success(String providerReference) {
        return new ProviderResult(ProviderOutcome.SUCCESS, providerReference, null);
    }

    public static ProviderResult pending(String providerReference) {
        return new ProviderResult(ProviderOutcome.PENDING, providerReference, null);
    }

    public static ProviderResult failed(String providerReference, String failureReason) {
        return new ProviderResult(ProviderOutcome.FAILED, providerReference, failureReason);
    }
}
