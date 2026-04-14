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
    private final SsdRepository ssdRepository;
    private final MainboardRepository mainboardRepository;
    private final PowerRepository powerRepository;
    private final CaseRepository caseRepository;

    public ResponseDTO.ResultList generateRecommendations(RequestDTO.EstimateRequest request) {

        Long budget = request.budget();

        // 1️⃣ 예산 분배 (현실적인 비율)
        long cpuBudget = (long)(budget * 0.25);
        long gpuBudget = (long)(budget * 0.35);
        long ramBudget = (long)(budget * 0.15);
        long ssdBudget = (long)(budget * 0.10);
        long mbBudget  = (long)(budget * 0.10);
        long powerBudget = (long)(budget * 0.05);

        // 2️⃣ 후보군 조회 (Top N 제한 중요!)
        List<Cpu> cpus = cpuRepository.findTop10ByPriceLessThanEqualOrderByPriceDesc(cpuBudget);
        List<Gpu> gpus = gpuRepository.findTop10ByPriceLessThanEqualOrderByPriceDesc(gpuBudget);
        List<Ram> rams = ramRepository.findTop5ByPriceLessThanEqualOrderByPriceDesc(ramBudget);
        List<Ssd> ssds = ssdRepository.findTop5ByPriceLessThanEqualOrderByPriceDesc(ssdBudget);
        List<Mainboard> mbs = mainboardRepository.findTop10ByPriceLessThanEqualOrderByPriceDesc(mbBudget);
        List<Power> powers = powerRepository.findTop5ByPriceLessThanEqualOrderByPriceDesc(powerBudget);
        List<Case> cases = caseRepository.findTop5ByPriceLessThanEqualOrderByPriceDesc(budget);

        List<ResponseDTO.Recommendation> results = new java.util.ArrayList<>();

        // 3️⃣ 조합 생성 (CPU 기준으로 돌림)
        for (Cpu cpu : cpus) {
            for (Mainboard mb : mbs) {

                // 🔥 호환성 체크 (핵심)
                if (!cpu.getSocketType().equals(mb.getSocketType())) continue;

                for (Gpu gpu : gpus) {
                    for (Ram ram : rams) {
                        for (Ssd ssd : ssds) {
                            for (Power power : powers) {
                                for (Case pcCase : cases) {

                                    long total =
                                            cpu.getPrice() +
                                                    gpu.getPrice() +
                                                    ram.getPrice() +
                                                    ssd.getPrice() +
                                                    mb.getPrice() +
                                                    power.getPrice() +
                                                    pcCase.getPrice();

                                    // 💰 예산 초과 컷
                                    if (total > budget) continue;

                                    // 🔥 점수 계산 (간단 버전)
                                    long score = gpu.getPrice() * 2 + cpu.getPrice();

                                    results.add(
                                            new ResponseDTO.Recommendation(
                                                    "추천 조합",
                                                    total,
                                                    List.of(
                                                            new ResponseDTO.PartDetail("CPU", cpu.getName(), cpu.getPrice(), "", ""),
                                                            new ResponseDTO.PartDetail("GPU", gpu.getName(), gpu.getPrice(), "", ""),
                                                            new ResponseDTO.PartDetail("RAM", ram.getName(), ram.getPrice(), "", ""),
                                                            new ResponseDTO.PartDetail("SSD", ssd.getName(), ssd.getPrice(), "", ""),
                                                            new ResponseDTO.PartDetail("MAINBOARD", mb.getName(), mb.getPrice(), "", ""),
                                                            new ResponseDTO.PartDetail("POWER", power.getName(), power.getPrice(), "", ""),
                                                            new ResponseDTO.PartDetail("CASE", pcCase.getName(), pcCase.getPrice(), "", "")
                                                    )
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4️⃣ 상위 N개만 반환
        results.sort((a, b) -> Long.compare(b.totalEstimatedPrice(), a.totalEstimatedPrice()));

        return new ResponseDTO.ResultList(
                results.stream().limit(3).toList()
        );
    }
}