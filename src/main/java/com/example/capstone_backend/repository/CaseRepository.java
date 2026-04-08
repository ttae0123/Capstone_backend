package com.example.capstone_backend.repository;

import com.example.capstone_backend.entity.Case;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<Case, Long>{
}
