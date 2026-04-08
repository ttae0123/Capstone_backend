package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Gpu;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GpuRepository extends JpaRepository<Gpu, Long> {
}
