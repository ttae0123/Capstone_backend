package com.example.capstone_backend;

import com.example.capstone_backend.service.MinimumBudgetService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Manually invoked after parts data loading; never runs during normal server startup. */
public final class MinimumBudgetInitializer {
    private MinimumBudgetInitializer() { }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CapstoneBackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(args)) {
            context.getBean(MinimumBudgetService.class).refreshAll();
            System.out.println("[OK] OFFICE / GAMING / VIDEO_EDITING 최소 예산 초기화 완료");
        }
    }
}
