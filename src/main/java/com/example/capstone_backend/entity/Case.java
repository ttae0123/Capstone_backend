package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(name = "pc_case")

public class Case {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(columnDefinition = "TEXT")
    private String size;

    @Column(name = "gpu_length")
    private Long gpuLength;

    @Column(name = "cooler_length")
    private Long coolerLength;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_url", nullable = false, columnDefinition = "TEXT")
    private String productUrl;
}