package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(name = "ssd")
public class Ssd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    private Long capacity;

    @Column(name = "bench_score")
    private Long benchScore;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_url", nullable = false, columnDefinition = "TEXT")
    private String productUrl;
}