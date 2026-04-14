package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Ram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RamRepository extends JpaRepository<Ram, Long>{
    List<Ram> findByPriceLessThanEqual(Long price);

    List<Ram> findTop5ByPriceLessThanEqualOrderByPriceDesc(long ramBudget);
}
