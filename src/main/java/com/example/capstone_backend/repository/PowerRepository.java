package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Cpu;
import com.example.capstone_backend.entity.Power;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PowerRepository extends JpaRepository<Power, Long>{
    List<Power> findByPriceLessThanEqual(Long price);

    List<Power> findTop5ByPriceLessThanEqualOrderByPriceDesc(long powerBudget);
}
