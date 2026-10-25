package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Cooler;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoolerRepository extends JpaRepository<Cooler, Long> {
    List<Cooler> findByPriceLessThanEqual(Long price);

    List<Cooler> findTop5ByPriceLessThanEqualOrderByPriceDesc(Long budget);
}
