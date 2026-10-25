package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(name = "ram")
public class Ram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(name = "memory_type", nullable = false, columnDefinition = "TEXT")
    private String memoryType;

    @Column(name = "memory_clock", nullable = false)
    private Long memoryClock;

    @Column(nullable = false)
    private Long capacity;

    @Column(name = "module_count", nullable = false)
    private Long moduleCount;

    @Column(name = "bench_score", nullable = false)
    private Double benchScore;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_url", nullable = false, columnDefinition = "TEXT")
    private String productUrl;
}