package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MerchantRequest(
        @NotBlank(message = "Business name is required.")
        @Size(max = 160, message = "Business name must be at most 160 characters.")
        String businessName,

        @Size(max = 64, message = "Registration number must be at most 64 characters.")
        String registrationNumber,

        @NotBlank(message = "Phone is required.")
        @Size(max = 32, message = "Phone must be at most 32 characters.")
        String phone,

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be valid.")
        @Size(max = 160, message = "Email must be at most 160 characters.")
        String email,

        @Size(max = 255, message = "Address must be at most 255 characters.")
        String address,

        /**
         * Optional link to the UserAccount holding the MERCHANT role for this
         * business. May be omitted and linked later.
         */
        Long ownerId
) {
}
