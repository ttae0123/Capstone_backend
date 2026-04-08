package com.example.capstone_backend.dto;

import java.util.List;

public class EstimateResponse {

    // 전체 응답: 여러 개의 추천 조합을 담는 리스트
    public record ResultList(
            List<Recommendation> recommendations
    ) {}

    // 추천 조합 하나 (예: 1번 조합 - 가성비 세팅)
    public record Recommendation(
            String rankName,          // 조합 명칭 (예: "최고 가성비 구성", "성능 중시 구성")
            Long totalEstimatedPrice, // 해당 조합의 합계 금액
            List<PartDetail> parts    // 포함된 부품 리스트
    ) {}

    // 개별 부품 상세 정보
    public record PartDetail(
            String category,  // 분류 (CPU, GPU, RAM, SSD 등)
            String name,      // 부품 모델명
            Long price,       // 부품 단가
            String brand,     // 제조사
            String specSummary // 주요 성능 요약 (예: "6코어 12스레드", "1TB")
    ) {}
}