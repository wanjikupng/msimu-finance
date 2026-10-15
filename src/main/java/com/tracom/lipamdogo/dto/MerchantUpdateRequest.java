package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.MerchantStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record MerchantUpdateRequest(
        @Size(max = 160, message = "Business name must be at most 160 characters.")
        String businessName,

        @Size(max = 64, message = "Registration number must be at most 64 characters.")
        String registrationNumber,

        @Size(max = 32, message = "Phone must be at most 32 characters.")
        String phone,

        @Email(message = "Email must be valid.")
        @Size(max = 160, message = "Email must be at most 160 characters.")
        String email,

        @Size(max = 255, message = "Address must be at most 255 characters.")
        String address,

        MerchantStatus status,

        Long ownerId
) {
}
