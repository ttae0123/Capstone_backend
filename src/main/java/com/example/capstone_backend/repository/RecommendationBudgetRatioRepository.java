package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.RecommendationBudgetRatio;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecommendationBudgetRatioRepository extends JpaRepository<RecommendationBudgetRatio, Long> {
    List<RecommendationBudgetRatio> findByUsageOrderByBudgetAsc(String usage);
}
