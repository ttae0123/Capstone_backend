package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(name = "cpu")
public class Cpu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(name = "socket_type", columnDefinition = "TEXT")
    private String socketType;

    @Column(name = "memory_type", columnDefinition = "TEXT")
    private String memoryType;

    @Column(name = "bench_score")
    private Long benchScore;

    @Column(nullable = false)
    private String brand;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_url", nullable = false, columnDefinition = "TEXT")
    private String productUrl;

}
