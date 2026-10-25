package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.ManualBuildSelection;
import com.example.capstone_backend.dto.ManualPartListResponse;
import com.example.capstone_backend.dto.ManualPartResponse;
import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수동 조립 부품 후보 서비스.
 *
 * 후보와 현재 선택된 부품을 동일한 BuildRules 규칙으로 비교한다.
 * 선택되지 않은 부품과의 호환성을 임의로 추정하지 않는다.
 * 사용된 DB에는 SSD 인터페이스/메인보드 M.2 슬롯/파워 커넥터 등의
 * 정보가 없으므로 해당 항목은 검사 범위에서 제외된다.
 */
@Service
@RequiredArgsConstructor
public class ManualBuildService {

    private final CpuRepository cpuRepository;
    private final MainboardRepository mainboardRepository;
    private final RamRepository ramRepository;
    private final GpuRepository gpuRepository;
    private final SsdRepository ssdRepository;
    private final PowerRepository powerRepository;
    private final CaseRepository caseRepository;
    private final CoolerRepository coolerRepository;

    public ManualPartListResponse getParts(String category, ManualBuildSelection selection) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("부품 카테고리가 비어 있습니다.");
        }

        String normalizedCategory = category.trim().toLowerCase(java.util.Locale.ROOT);

        if (normalizedCategory.equals("cpu")) {
            return getCpuParts(selection);
        }

        if (normalizedCategory.equals("mainboard")) {
            return getMainboardParts(selection);
        }

        if (normalizedCategory.equals("ram")) {
            return getRamParts(selection);
        }

        if (normalizedCategory.equals("gpu")) {
            return getGpuParts(selection);
        }

        if (normalizedCategory.equals("ssd")) {
            return getSsdParts();
        }

        if (normalizedCategory.equals("power")) {
            return getPowerParts(selection);
        }

        if (normalizedCategory.equals("case")) {
            return getCaseParts(selection);
        }

        if (normalizedCategory.equals("cooler")) {
            return getCoolerParts(selection);
        }

        throw new IllegalArgumentException("지원하지 않는 부품 카테고리입니다: " + category);
    }

    private ManualPartListResponse getCpuParts(ManualBuildSelection selection) {
        List<Cpu> cpus = cpuRepository.findAll();

        if (selection != null && selection.mainboardId() != null) {
            Mainboard selectedMainboard = getMainboardById(selection.mainboardId());

            cpus = cpus.stream()
                    .filter(cpu -> BuildRules.cpuMainboard(cpu, selectedMainboard))
                    .toList();
        }

        if (selection != null && selection.coolerId() != null) {
            Cooler selectedCooler = getCoolerById(selection.coolerId());

            cpus = cpus.stream()
                    .filter(cpu -> BuildRules.cpuCooler(cpu, selectedCooler))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Cpu cpu : cpus) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("socketType", cpu.getSocketType());
            specs.put("memoryType", cpu.getMemoryType());
            specs.put("benchScore", cpu.getBenchScore());

            ManualPartResponse part = new ManualPartResponse(
                    cpu.getId(),
                    "CPU",
                    cpu.getName(),
                    cpu.getPrice(),
                    cpu.getBrand(),
                    null,
                    cpu.getProductCode(),
                    cpu.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("cpu", parts.size(), parts);
    }

    private ManualPartListResponse getMainboardParts(ManualBuildSelection selection) {
        List<Mainboard> mainboards = mainboardRepository.findAll();

        if (selection != null && selection.cpuId() != null) {
            Cpu selectedCpu = getCpuById(selection.cpuId());

            mainboards = mainboards.stream()
                    .filter(mainboard -> BuildRules.cpuMainboard(selectedCpu, mainboard))
                    .toList();
        }

        if (selection != null && selection.ramId() != null) {
            Ram selectedRam = getRamById(selection.ramId());

            mainboards = mainboards.stream()
                    .filter(mainboard -> BuildRules.ramBoard(selectedRam, mainboard))
                    .toList();
        }

        if (selection != null && selection.gpuId() != null) {
            Gpu selectedGpu = getGpuById(selection.gpuId());

            mainboards = mainboards.stream()
                    .filter(mainboard -> BuildRules.gpuMainboard(selectedGpu, mainboard))
                    .toList();
        }

        if (selection != null && selection.caseId() != null) {
            Case selectedCase = getCaseById(selection.caseId());

            mainboards = mainboards.stream()
                    .filter(mainboard -> BuildRules.boardCase(mainboard, selectedCase))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Mainboard mainboard : mainboards) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("socketType", mainboard.getSocketType());
            specs.put("memoryType", mainboard.getMemoryType());
            specs.put("pcieType", mainboard.getPcieType());
            specs.put("size", mainboard.getSize());
            specs.put("memoryClock", mainboard.getMemoryClock());

            ManualPartResponse part = new ManualPartResponse(
                    mainboard.getId(),
                    "MAINBOARD",
                    mainboard.getName(),
                    mainboard.getPrice(),
                    mainboard.getBrand(),
                    null,
                    mainboard.getProductCode(),
                    mainboard.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("mainboard", parts.size(), parts);
    }

    private ManualPartListResponse getRamParts(ManualBuildSelection selection) {
        List<Ram> rams = ramRepository.findAll();

        if (selection != null && selection.mainboardId() != null) {
            Mainboard selectedMainboard = getMainboardById(selection.mainboardId());

            rams = rams.stream()
                    .filter(ram -> BuildRules.ramBoard(ram, selectedMainboard))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Ram ram : rams) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("memoryType", ram.getMemoryType());
            specs.put("memoryClock", ram.getMemoryClock());
            specs.put("capacity", ram.getCapacity());
            specs.put("moduleCount", ram.getModuleCount());
            specs.put("benchScore", ram.getBenchScore());

            ManualPartResponse part = new ManualPartResponse(
                    ram.getId(),
                    "RAM",
                    ram.getName(),
                    ram.getPrice(),
                    null,
                    null,
                    ram.getProductCode(),
                    ram.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("ram", parts.size(), parts);
    }

    private ManualPartListResponse getGpuParts(ManualBuildSelection selection) {
        List<Gpu> gpus = gpuRepository.findAll();

        if (selection != null && selection.mainboardId() != null) {
            Mainboard selectedMainboard = getMainboardById(selection.mainboardId());

            gpus = gpus.stream()
                    .filter(gpu -> BuildRules.gpuMainboard(gpu, selectedMainboard))
                    .toList();
        }

        if (selection != null && selection.caseId() != null) {
            Case selectedCase = getCaseById(selection.caseId());

            gpus = gpus.stream()
                    .filter(gpu -> BuildRules.gpuCase(gpu, selectedCase))
                    .toList();
        }

        if (selection != null && selection.powerId() != null) {
            Power selectedPower = getPowerById(selection.powerId());

            gpus = gpus.stream()
                    .filter(gpu -> BuildRules.power(gpu, selectedPower, 0L))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Gpu gpu : gpus) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("pcieType", gpu.getPcieType());
            specs.put("gpuLength", gpu.getGpuLength());
            specs.put("recommendedPower", gpu.getRecommendedPower());
            specs.put("benchScore", gpu.getBenchScore());

            ManualPartResponse part = new ManualPartResponse(
                    gpu.getId(),
                    "GPU",
                    gpu.getName(),
                    gpu.getPrice(),
                    gpu.getBrand(),
                    gpu.getChipsetBrand(),
                    gpu.getProductCode(),
                    gpu.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("gpu", parts.size(), parts);
    }

    private ManualPartListResponse getSsdParts() {
        List<Ssd> ssds = ssdRepository.findAll();

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Ssd ssd : ssds) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("capacity", ssd.getCapacity());
            specs.put("benchScore", ssd.getBenchScore());

            ManualPartResponse part = new ManualPartResponse(
                    ssd.getId(),
                    "SSD",
                    ssd.getName(),
                    ssd.getPrice(),
                    null,
                    null,
                    ssd.getProductCode(),
                    ssd.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("ssd", parts.size(), parts);
    }

    private ManualPartListResponse getPowerParts(ManualBuildSelection selection) {
        List<Power> powers = powerRepository.findAll();

        if (selection != null && selection.gpuId() != null) {
            Gpu selectedGpu = getGpuById(selection.gpuId());

            powers = powers.stream()
                    .filter(power -> BuildRules.power(selectedGpu, power, 0L))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Power power : powers) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("size", power.getSize());
            specs.put("wattage", power.getWattage());

            ManualPartResponse part = new ManualPartResponse(
                    power.getId(),
                    "POWER",
                    power.getName(),
                    power.getPrice(),
                    null,
                    null,
                    power.getProductCode(),
                    power.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("power", parts.size(), parts);
    }

    private ManualPartListResponse getCaseParts(ManualBuildSelection selection) {
        List<Case> cases = caseRepository.findAll();

        if (selection != null && selection.mainboardId() != null) {
            Mainboard selectedMainboard = getMainboardById(selection.mainboardId());

            cases = cases.stream()
                    .filter(pcCase -> BuildRules.boardCase(selectedMainboard, pcCase))
                    .toList();
        }

        if (selection != null && selection.gpuId() != null) {
            Gpu selectedGpu = getGpuById(selection.gpuId());

            cases = cases.stream()
                    .filter(pcCase -> BuildRules.gpuCase(selectedGpu, pcCase))
                    .toList();
        }

        if (selection != null && selection.coolerId() != null) {
            Cooler selectedCooler = getCoolerById(selection.coolerId());

            cases = cases.stream()
                    .filter(pcCase -> BuildRules.coolerCase(selectedCooler, pcCase))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Case pcCase : cases) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("size", pcCase.getSize());
            specs.put("gpuLength", pcCase.getGpuLength());
            specs.put("coolerLength", pcCase.getCoolerLength());

            ManualPartResponse part = new ManualPartResponse(
                    pcCase.getId(),
                    "CASE",
                    pcCase.getName(),
                    pcCase.getPrice(),
                    null,
                    null,
                    pcCase.getProductCode(),
                    pcCase.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("case", parts.size(), parts);
    }

    private ManualPartListResponse getCoolerParts(ManualBuildSelection selection) {
        List<Cooler> coolers = coolerRepository.findAll();

        if (selection != null && selection.cpuId() != null) {
            Cpu selectedCpu = getCpuById(selection.cpuId());

            coolers = coolers.stream()
                    .filter(cooler -> BuildRules.cpuCooler(selectedCpu, cooler))
                    .toList();
        }

        if (selection != null && selection.caseId() != null) {
            Case selectedCase = getCaseById(selection.caseId());

            coolers = coolers.stream()
                    .filter(cooler -> BuildRules.coolerCase(cooler, selectedCase))
                    .toList();
        }

        List<ManualPartResponse> parts = new ArrayList<>();

        for (Cooler cooler : coolers) {
            Map<String, Object> specs = new LinkedHashMap<>();
            specs.put("socketType", cooler.getSocketType());
            specs.put("coolerLength", cooler.getCoolerLength());

            ManualPartResponse part = new ManualPartResponse(
                    cooler.getId(),
                    "COOLER",
                    cooler.getName(),
                    cooler.getPrice(),
                    null,
                    null,
                    cooler.getProductCode(),
                    cooler.getProductUrl(),
                    specs
            );

            parts.add(part);
        }

        return new ManualPartListResponse("cooler", parts.size(), parts);
    }

    private Cpu getCpuById(Long cpuId) {
        return cpuRepository.findById(cpuId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 CPU입니다. cpuId = " + cpuId
                        )
                );
    }

    private Mainboard getMainboardById(Long mainboardId) {
        return mainboardRepository.findById(mainboardId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 메인보드입니다. mainboardId = " + mainboardId
                        )
                );
    }

    private Ram getRamById(Long ramId) {
        return ramRepository.findById(ramId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 RAM입니다. ramId = " + ramId
                        )
                );
    }

    private Gpu getGpuById(Long gpuId) {
        return gpuRepository.findById(gpuId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 GPU입니다. gpuId = " + gpuId
                        )
                );
    }

    private Power getPowerById(Long powerId) {
        return powerRepository.findById(powerId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 파워입니다. powerId = " + powerId
                        )
                );
    }

    private Case getCaseById(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 케이스입니다. caseId = " + caseId
                        )
                );
    }

    private Cooler getCoolerById(Long coolerId) {
        return coolerRepository.findById(coolerId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 쿨러입니다. coolerId = " + coolerId
                        )
                );
    }

}
