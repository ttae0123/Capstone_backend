package com.example.capstone_backend.controller;

import com.example.capstone_backend.dto.*;
import com.example.capstone_backend.service.EstimateService;
import com.example.capstone_backend.service.OwnedEstimateService;
import com.example.capstone_backend.service.OpenAiService;
import com.example.capstone_backend.service.MinimumBudgetService;
import com.example.capstone_backend.service.NaturalLanguageEstimateService;
import com.example.capstone_backend.service.BudgetRatioService;
import com.example.capstone_backend.entity.RecommendationMinimumBudget;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/estimates")
@CrossOrigin(origins = "http://localhost:5173")
public class EstimateController {

    private final EstimateService estimateService;
    private final OwnedEstimateService ownedEstimateService;
    private final OpenAiService openAiService;
    private final MinimumBudgetService minimumBudgetService;
    private final NaturalLanguageEstimateService naturalLanguageEstimateService;


    public EstimateController(EstimateService estimateService, OwnedEstimateService ownedEstimateService,
                              OpenAiService openAiService, MinimumBudgetService minimumBudgetService,
                              NaturalLanguageEstimateService naturalLanguageEstimateService) {
        this.estimateService = estimateService;
        this.ownedEstimateService = ownedEstimateService;
        this.openAiService = openAiService;
        this.minimumBudgetService = minimumBudgetService;
        this.naturalLanguageEstimateService = naturalLanguageEstimateService;
    }

    /** 프론트의 용도 선선택 UI: 최소·최대 허용 예산. */
    @GetMapping("/budget-ranges")
    public List<MinimumBudgetService.BudgetRange> budgetRanges() {
        return minimumBudgetService.currentRanges();
    }

    @GetMapping("/minimum-budgets")
    public List<RecommendationMinimumBudget> minimumBudgets() {
        return minimumBudgetService.currentMinimums();
    }

    // 1. 기존 DB 추천 API
    // 버튼 선택 방식
    @PostMapping("/recommend")
    public ResponseDTO.ResultList getRecommendation(
            @RequestBody RequestDTO.EstimateRequest request
    ) {

        return estimateService.generateRecommendations(
                request
        );
    }


    /** 보유 부품을 실제 DB ID로 고정한 서버측 자동 견적. */
    @PostMapping("/recommend-with-owned")
    public ResponseDTO.ResultList recommendWithOwned(@RequestBody OwnedEstimateRequest request) {
        return ownedEstimateService.generate(request);
    }

    // 2. AI 분석 API
    // 추천 결과 장단점 분석
    @PostMapping("/analyze")
    public ResponseEntity<AiAnalysisResponse> analyzeEstimate(
            @RequestBody AiAnalysisRequest request
    ) {

        AiAnalysisResponse analysis =
                openAiService.analyzeParts(
                        request
                );

        return ResponseEntity.ok(
                analysis
        );
    }


    /**
     * 자연어 예산/용도 해석 및 충돌 확인.
     * READY: 기존 추천 3개 바로 반환.
     * CONFIRMATION_REQUIRED: options 두 가지 중 선택, 별도 confirm API로 후속 요청.
     */
    @PostMapping("/natural-language")
    public ResponseEntity<Map<String, Object>> naturalLanguage(@RequestBody Map<String, String> payload) {
        if (payload == null) throw new IllegalArgumentException("자연어 견적 요청 내용을 입력해주세요.");
        NaturalLanguageEstimateService.Decision decision =
                naturalLanguageEstimateService.resolve(payload.get("userInput"));
        if ("CONFIRMATION_REQUIRED".equals(decision.status())) {
            return ResponseEntity.ok(Map.of(
                    "status", decision.status(),
                    "message", decision.prompt(),
                    "options", decision.options()));
        }
        NaturalLanguageEstimateService.Plan plan = decision.plan();
        ResponseDTO.ResultList results = naturalLanguageEstimateService.generate(plan);
        return ResponseEntity.ok(Map.of(
                "status", "READY",
                "recommendations", results.recommendations(),
                "userBudget", plan.budget(),
                "usage", plan.usage(),
                "intent", plan.intent(),
                "gameName", plan.gameName(),
                "referenceGame", plan.referenceGame(),
                "warnings", plan.warnings()));
    }

    /** 화면에서 선택한 예산/용도를 확인한 후 재분석 없이 추천 엔진을 호출한다. */
    @PostMapping("/natural-language/confirm")
    public ResponseEntity<Map<String, Object>> confirmNaturalLanguage(
            @RequestBody NaturalLanguageEstimateService.ConfirmRequest request) {
        ResponseDTO.ResultList result = naturalLanguageEstimateService.confirm(request);
        return ResponseEntity.ok(Map.of(
                "status", "READY",
                "recommendations", result.recommendations(),
                "userBudget", request.budget(),
                "usage", BudgetRatioService.normalizeUsage(request.usage()),
                "warnings", List.of()));
    }
}
