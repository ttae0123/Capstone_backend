package com.example.capstone_backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class ResponseDTO {

    public record ResultList(
            List<Recommendation> recommendations
    ) {
    }


    public record Recommendation(
            String rankName,
            Long totalEstimatedPrice,
            List<PartDetail> parts
    ) {
    }


    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartDetail(
            String category,
            String name,
            Long price,
            String brand,
            String chipsetBrand,
            Number benchScore,
            String specSummary,
            Long productCode,
            String productUrl,
            Long id,
            Boolean isOwned
    ) {
        /** Keeps existing nine-argument recommend/analysis code compatible. */
        public PartDetail(String category, String name, Long price, String brand,
                          String chipsetBrand, Number benchScore, String specSummary,
                          Long productCode, String productUrl) {
            this(category, name, price, brand, chipsetBrand, benchScore,
                    specSummary, productCode, productUrl, null, null);
        }
    }
}