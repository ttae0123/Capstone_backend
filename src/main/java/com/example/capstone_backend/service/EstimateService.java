package com.example.capstone_backend.service;


import com.example.capstone_backend.dto.ResponseDTO;
import com.example.capstone_backend.dto.RequestDTO;
import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EstimateService {

    private final CpuRepository cpuRepository;
    private final GpuRepository gpuRepository;
    private final RamRepository ramRepository;

    public ResponseDTO.ResultList generateRecommendations(RequestDTO.EstimateRequest request) {

        Long budget = request.budget();

        // 간단하게 비율 나눔 (CPU 30%, GPU 50%, RAM 20%)
        Long cpuBudget = (long)(budget * 0.3);
        Long gpuBudget = (long)(budget * 0.5);
        Long ramBudget = (long)(budget * 0.2);

        // DB 조회
        List<Cpu> cpus = cpuRepository.findByPriceLessThanEqual(cpuBudget);
        List<Gpu> gpus = gpuRepository.findByPriceLessThanEqual(gpuBudget);
        List<Ram> rams = ramRepository.findByPriceLessThanEqual(ramBudget);

        // 가장 비싼(=성능 좋은) 부품 선택
        Cpu cpu = cpus.stream()
                .max(Comparator.comparingLong(Cpu::getPrice))
                .orElseThrow();

        Gpu gpu = gpus.stream()
                .max(Comparator.comparingLong(Gpu::getPrice))
                .orElseThrow();

        Ram ram = rams.stream()
                .max(Comparator.comparingLong(Ram::getPrice))
                .orElseThrow();

        // 추천 1 (가성비)
        ResponseDTO.Recommendation rec1 = new ResponseDTO.Recommendation(
                "가성비 추천",
                cpu.getPrice() + gpu.getPrice() + ram.getPrice(),
                List.of(
                        new ResponseDTO.PartDetail("CPU", cpu.getName(), cpu.getPrice(), "AMD/Intel", "자동 추천"),
                        new ResponseDTO.PartDetail("GPU", gpu.getName(), gpu.getPrice(), "NVIDIA/AMD", "자동 추천"),
                        new ResponseDTO.PartDetail("RAM", ram.getName(), ram.getPrice(), "삼성/하이닉스", "자동 추천")
                )
        );

        // 추천 2 (성능형 → GPU 조금 더 좋은 걸로)
        Gpu highGpu = gpuRepository.findAll().stream()
                .max(Comparator.comparingLong(Gpu::getPrice))
                .orElse(gpu);

        ResponseDTO.Recommendation rec2 = new ResponseDTO.Recommendation(
                request.usage() + " 성능형 추천",
                cpu.getPrice() + highGpu.getPrice() + ram.getPrice(),
                List.of(
                        new ResponseDTO.PartDetail("CPU", cpu.getName(), cpu.getPrice(), "AMD/Intel", "고성능"),
                        new ResponseDTO.PartDetail("GPU", highGpu.getName(), highGpu.getPrice(), "NVIDIA/AMD", "상위 GPU"),
                        new ResponseDTO.PartDetail("RAM", ram.getName(), ram.getPrice(), "삼성/하이닉스", "고용량")
                )
        );

        return new ResponseDTO.ResultList(List.of(rec1, rec2));
    }
}