package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Cpu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CpuRepository extends JpaRepository<Cpu,Long> {
    List<Cpu> findByPriceLessThanEqual(Long price);
}
