package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Cpu;
import com.example.capstone_backend.entity.Ssd;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SsdRepository extends JpaRepository<Ssd, Long>{
    List<Ssd> findByPriceLessThanEqual(Long price);

    List<Ssd> findTop5ByPriceLessThanEqualOrderByPriceDesc(long ssdBudget);
}
