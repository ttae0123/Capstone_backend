package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Case;
import com.example.capstone_backend.entity.Cpu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseRepository extends JpaRepository<Case, Long>{
    List<Case> findByPriceLessThanEqual(Long price);

    List<Case> findTop5ByPriceLessThanEqualOrderByPriceDesc(Long budget);
}
