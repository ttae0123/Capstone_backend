package com.example.capstone_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The client only identifies parts; prices and names are reloaded from the DB. */
public record EstimateEmailRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 100) String rankName,
        @NotNull @Positive Long budget,
        @NotEmpty @Size(max = 8) List<@Valid PartRef> parts
) {
    public record PartRef(
            @NotBlank @Size(max = 30) String category,
            Long productCode,
            Long id,
            Boolean isOwned
    ) { }
}
