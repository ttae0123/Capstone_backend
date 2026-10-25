package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(name = "gpu")
public class Gpu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(name = "recommended_power")
    private Long recommendedPower;

    @Column(name = "pcie_type", columnDefinition = "TEXT")
    private String pcieType;

    @Column(name = "gpu_length")
    private Long gpuLength;

    @Column(name = "bench_score")
    private Long benchScore;

    @Column(nullable = false)
    private String brand;

    @Column(name = "chipset_brand", nullable = false)
    private String chipsetBrand;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_url", nullable = false, columnDefinition = "TEXT")
    private String productUrl;
}