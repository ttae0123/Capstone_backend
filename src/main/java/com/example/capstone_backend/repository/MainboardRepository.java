package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Cpu;
import com.example.capstone_backend.entity.Mainboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MainboardRepository extends JpaRepository<Mainboard, Long>{
    List<Mainboard> findByPriceLessThanEqual(Long price);

    List<Mainboard> findTop10ByPriceLessThanEqualOrderByPriceDesc(long mbBudget);
}
