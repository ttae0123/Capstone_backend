package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(name = "mainboard")
public class Mainboard {

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

    @Column(name = "pcie_type", columnDefinition = "TEXT")
    private String pcieType;

    @Column(columnDefinition = "TEXT")
    private String size;

    @Column(name = "memory_clock")
    private Long memoryClock;
}