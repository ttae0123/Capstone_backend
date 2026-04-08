package com.example.capstone_backend.service;


import com.example.capstone_backend.dto.EstimateResponse;
import com.example.capstone_backend.dto.RequestDTO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EstimateService {

    public EstimateResponse.ResultList generateRecommendations(RequestDTO.EstimateRequest request) {
        // 실제 구현 시에는 여기서 Repository를 통해 MariaDB에서 부품을 조회합니다.
        // 현재는 테스트를 위해 하드코딩된 데이터를 반환합니다.

        // 1번 조합: 가성비 세팅
        EstimateResponse.Recommendation rec1 = new EstimateResponse.Recommendation(
                "최고의 가성비 조합",
                850000L,
                List.of(
                        new EstimateResponse.PartDetail("CPU", "AMD 라이젠5 7600", 250000L, "AMD", "6코어 12스레드"),
                        new EstimateResponse.PartDetail("GPU", "RTX 4060", 400000L, "NVIDIA", "FHD 게이밍 최적화"),
                        new EstimateResponse.PartDetail("RAM", "DDR5 16GB", 70000L, "삼성전자", "5600MHz")
                )
        );

        // 2번 조합: 예산 꽉 채운 성능형 (사용자가 보낸 budget에 맞춰 조정하는 척)
        EstimateResponse.Recommendation rec2 = new EstimateResponse.Recommendation(
                request.usage() + " 맞춤형 성능 조합",
                request.budget(),
                List.of(
                        new EstimateResponse.PartDetail("CPU", "인텔 코어i7 14세대", 550000L, "Intel", "고성능 작업용"),
                        new EstimateResponse.PartDetail("GPU", "RTX 4070 Super", 850000L, "NVIDIA", "QHD 게이밍"),
                        new EstimateResponse.PartDetail("RAM", "DDR5 32GB", 140000L, "SK하이닉스", "고성능 튜닝램")
                )
        );

        return new EstimateResponse.ResultList(List.of(rec1, rec2));
    }
}