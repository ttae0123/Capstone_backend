package com.example.capstone_backend;

import com.example.capstone_backend.service.MinimumBudgetService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** 부품 적재 완료 후 수동 실행. 일반 웹 서버 실행에는 영향을 주지 않는다. */
public final class MinimumBudgetInitializer {
    private MinimumBudgetInitializer() { }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CapstoneBackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(args)) {
            MinimumBudgetService service = context.getBean(MinimumBudgetService.class);
            service.refreshAll();
            System.out.println("[OK] OFFICE / GAMING / HIGH_END 최소 예산 초기화 완료");
            for (MinimumBudgetService.BudgetRange range : service.currentRanges()) {
                System.out.printf("[%s] 최소 %,d원 / 최대 %s%n", range.usage(),
                        range.minBudget(), range.maxBudget() == null ? "미설정" : String.format("%,d원", range.maxBudget()));
            }
        }
    }
}
