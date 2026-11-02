package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.Case;
import com.example.capstone_backend.entity.Cooler;
import com.example.capstone_backend.entity.Cpu;
import com.example.capstone_backend.entity.Gpu;
import com.example.capstone_backend.entity.Mainboard;
import com.example.capstone_backend.entity.Power;
import com.example.capstone_backend.entity.Ram;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 현재 DB에 저장된 규격에 근거한 PC 부품 호환성 검사.
 *
 * [물리적 호환성] cpuMainboard, cpuCooler, ramBoard, gpuMainboard,
 * boardCase, gpuCase, coolerCase, power, standardAtxPower, integratedGraphics
 *
 * [추천 품질] cpuMainboardBalanced, ramBoardSpeedMatched,
 * gpuMainboardPcieMatched, gpuCaseWithClearance, coolerCaseWithClearance
 *
 * 중요:
 * - 추천 품질 불일치를 하드웨어 비호환으로 표시하지 않는다.
 * - BIOS 지원 CPU 목록, RAM QVL, 전원부 품질/발열, 파워 품질/커넥터,
 *   SSD 연결 규격, 케이스 통풍 및 쿨러 냉각 성능은 현재 DB 컬럼으로
 *   검증할 수 없으므로 이 클래스가 보장하지 않는다.
 * - 알 수 없는 필수 물리적 규격은 호환으로 추정하지 않는다.
 */
public final class BuildRules {

    private static final Pattern PCIE_PATTERN =
            Pattern.compile("^(?:PCIE|PCIEXPRESS)([1-7])(?:\\.\\d+)?X(1|2|4|8|16)$");

    private static final Pattern INTEL_NO_IGPU =
            Pattern.compile("\\b\\d{3,5}(?:KF|F)\\b");
    private static final Pattern AMD_NO_IGPU =
            Pattern.compile("\\b\\d{4,5}F\\b");
    private static final Pattern AMD_APU =
            Pattern.compile("\\b\\d{4,5}(?:G|GE|GT)\\b");
    private static final Pattern AMD_AM5_DESKTOP =
            Pattern.compile("\\b(?:7|9)\\d{3}(?:X3D\\d?|X|XT)?\\b");

    // 케이스의 실제 간섭 가능성을 고려하는 '추천' 여유 값.
    // 제품의 물리적 장착 가능 여부 판단과는 별개로 사용한다.
    public static final long RECOMMENDED_GPU_CLEARANCE_MM = 20L;
    public static final long RECOMMENDED_COOLER_CLEARANCE_MM = 5L;

    private BuildRules() {
    }

    public static boolean positive(Long value) {
        return value != null && value > 0;
    }

    // RAM/메인보드 클럭의 Entity 숫자 타입(Long/Integer)에 모두 대응한다.
    private static boolean positiveClock(Number value) {
        return value != null && value.longValue() > 0;
    }

    private static String token(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9가-힣]", "");
    }

    private static String socket(String value) {
        String normalized = token(value)
                .replace("SOCKET", "")
                .replace("소켓", "")
                .replace("지원", "")
                .replace("INTEL", "")
                .replace("인텔", "")
                .replace("LGA", "");

        if (normalized.equals("AM4") || normalized.equals("AMD4")) {
            return "AMD4";
        }
        if (normalized.equals("AM5") || normalized.equals("AMD5")) {
            return "AMD5";
        }
        return normalized;
    }

    /** CPU-메인보드: 소켓 정보가 있어야 하며 정확히 일치해야 한다. */
    public static boolean cpuMainboard(Cpu cpu, Mainboard board) {
        if (cpu == null || board == null) {
            return false;
        }
        String cpuSocket = socket(cpu.getSocketType());
        String boardSocket = socket(board.getSocketType());
        return !cpuSocket.isEmpty() && cpuSocket.equals(boardSocket);
    }

    /**
     * CPU-메인보드 추천 균형.
     * 고성능 CPU(인텔 Core i7/i9, Core Ultra 7/9, Ryzen 7/9)에
     * H310/H410/H510/H610/H810, A320/A520/A620 보드를 추천하지 않는다.
     * 이는 소켓 호환성 실패가 아니라 '추천 후보 제외' 규칙이다.
     *
     * 참고: 실제 전원부 및 BIOS 적합성은 DB 정보만으로 확인 불가하다.
     */
    public static boolean cpuMainboardBalanced(Cpu cpu, Mainboard board) {
        if (!cpuMainboard(cpu, board)) {
            return false;
        }
        if (!isHighPerformanceCpu(cpu)) {
            return true;
        }
        return !isEntryLevelMainboard(board);
    }

    private static boolean isHighPerformanceCpu(Cpu cpu) {
        if (cpu == null) {
            return false;
        }
        String name = token(cpu.getName());
        return name.contains("코어I7") || name.contains("COREI7")
                || name.contains("코어I9") || name.contains("COREI9")
                || name.contains("코어울트라7") || name.contains("COREULTRA7")
                || name.contains("울트라7") || name.contains("ULTRA7")
                || name.contains("코어울트라9") || name.contains("COREULTRA9")
                || name.contains("울트라9") || name.contains("ULTRA9")
                || name.contains("라이젠7") || name.contains("RYZEN7")
                || name.contains("라이젠9") || name.contains("RYZEN9");
    }

    private static boolean isEntryLevelMainboard(Mainboard board) {
        String name = board == null ? "" : token(board.getName());
        return name.contains("H310") || name.contains("H410")
                || name.contains("H510") || name.contains("H610")
                || name.contains("H810") || name.contains("A320")
                || name.contains("A520") || name.contains("A620");
    }

    /**
     * CPU-쿨러: 구분된 지원 소켓 목록을 비교한다.
     * LGA115x와 LGA1200은 장착 홀 간격이 동일한 계열로 취급한다.
     * 단, LGA1700과 LGA1851은 같은 계열이라고 자동 추정하지 않는다.
     */
    public static boolean cpuCooler(Cpu cpu, Cooler cooler) {
        if (cpu == null || cooler == null) {
            return false;
        }
        String target = socket(cpu.getSocketType());
        String supportedText = cooler.getSocketType();
        if (target.isEmpty() || supportedText == null || supportedText.isBlank()) {
            return false;
        }

        for (String raw : supportedText.split("[,;/|\\s]+")) {
            String supported = socket(raw);
            if (supported.isEmpty()) {
                continue;
            }
            if (target.equals(supported)) {
                return true;
            }
            if (intel115xOr1200(target) && intel115xOr1200(supported)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intel115xOr1200(String value) {
        return value.equals("115X") || value.equals("1150")
                || value.equals("1151") || value.equals("1151V2")
                || value.equals("1155") || value.equals("1156")
                || value.equals("1200");
    }

    /**
     * RAM-메인보드의 필수 호환성: DDR 세대가 같아야 한다.
     * RAM 스펙상 클럭이 보드 표기 최대치를 초과해도,
     * 클럭을 낮춰 작동할 수 있으므로 여기서는 거부하지 않는다.
     */
    public static boolean ramBoard(Ram ram, Mainboard board) {
        if (ram == null || board == null) {
            return false;
        }
        String ramType = token(ram.getMemoryType());
        String boardType = token(board.getMemoryType());
        return !ramType.isEmpty() && ramType.equals(boardType);
    }

    /**
     * RAM-메인보드 추천 속도 적합성.
     * DDR 세대가 일치하고 표시 최대 클럭 범위 안에 있어야 true.
     * false는 작동 불가가 아니라 '표기 속도 전부 활용을 보장할 수 없음'이다.
     * CPU 메모리 컨트롤러와 XMP/EXPO/QVL 검증은 별개이다.
     */
    public static boolean ramBoardSpeedMatched(Ram ram, Mainboard board) {
        return ramBoard(ram, board)
                && positiveClock(ram.getMemoryClock())
                && positiveClock(board.getMemoryClock())
                && ram.getMemoryClock() <= board.getMemoryClock();
    }

    /**
     * GPU-메인보드: PCIe 세대가 달라도 연결 가능하다.
     * 메인보드 측 x16 슬롯과 GPU 링크 레인 수가 확인된 경우만 통과한다.
     */
    public static boolean gpuMainboard(Gpu gpu, Mainboard board) {
        if (board == null) {
            return false;
        }
        if (gpu == null) { // 외장 GPU 없는 구성
            return true;
        }
        PcieSpec boardSpec = parsePcie(board.getPcieType());
        PcieSpec gpuSpec = parsePcie(gpu.getPcieType());
        return boardSpec != null && gpuSpec != null
                && boardSpec.lanes() == 16
                && gpuSpec.lanes() > 0 && gpuSpec.lanes() <= 16;
    }

    /**
     * PCIe 추천 적합성: 메인보드 PCIe 세대가 GPU보다 낮지 않은지 확인.
     * false여도 물리적 비호환이라는 뜻은 아니다.
     */
    public static boolean gpuMainboardPcieMatched(Gpu gpu, Mainboard board) {
        if (!gpuMainboard(gpu, board)) {
            return false;
        }
        if (gpu == null) {
            return true;
        }
        PcieSpec boardSpec = parsePcie(board.getPcieType());
        PcieSpec gpuSpec = parsePcie(gpu.getPcieType());
        return boardSpec.generation() >= gpuSpec.generation();
    }

    private static PcieSpec parsePcie(String value) {
        if (value == null) {
            return null;
        }
        // PCIe 5.0 x16, PCI-E 4.0 x8, PCI Express 3.0 x16 등을 통일.
        String normalized = value.toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
        Matcher matcher = PCIE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        int generation = Integer.parseInt(matcher.group(1));
        int lanes = Integer.parseInt(matcher.group(2));
        return new PcieSpec(generation, lanes);
    }

    private record PcieSpec(int generation, int lanes) {
    }

    /** 메인보드-케이스: 현재 DB의 폼팩터 상한 기준으로 판단한다. */
    public static boolean boardCase(Mainboard board, Case pcCase) {
        if (board == null || pcCase == null) {
            return false;
        }
        int boardRank = sizeRank(token(board.getSize()));
        int caseRank = sizeRank(token(pcCase.getSize()));
        return boardRank > 0 && caseRank >= boardRank;
    }

    private static int sizeRank(String size) {
        if (size.equals("EATX") || size.equals("EXTENDEDATX")) {
            return 4;
        }
        if (size.equals("ATX")) {
            return 3;
        }
        if (size.equals("MATX") || size.equals("MICROATX")) {
            return 2;
        }
        if (size.equals("ITX") || size.equals("MINIITX")) {
            return 1;
        }
        return 0;
    }

    /** GPU-케이스: 길이 정보가 모두 유효하고 케이스 최대 허용 길이 이하여야 한다. */
    public static boolean gpuCase(Gpu gpu, Case pcCase) {
        if (gpu == null) {
            return true;
        }
        return pcCase != null && positive(gpu.getGpuLength())
                && positive(pcCase.getGpuLength())
                && gpu.getGpuLength() <= pcCase.getGpuLength();
    }

    /** GPU-케이스 추천 장착 여유: 지정한 최소 길이 여유를 요구한다. */
    public static boolean gpuCaseWithClearance(Gpu gpu, Case pcCase, long clearanceMm) {
        if (clearanceMm < 0 || !gpuCase(gpu, pcCase)) {
            return false;
        }
        if (gpu == null) {
            return true;
        }
        return pcCase.getGpuLength() - gpu.getGpuLength() >= clearanceMm;
    }

    /** CPU 쿨러-케이스: 쿨러 높이가 케이스 최대 허용 높이 이하여야 한다. */
    public static boolean coolerCase(Cooler cooler, Case pcCase) {
        return cooler != null && pcCase != null
                && positive(cooler.getCoolerLength())
                && positive(pcCase.getCoolerLength())
                && cooler.getCoolerLength() <= pcCase.getCoolerLength();
    }

    /** 쿨러-케이스 추천 장착 여유: 지정한 최소 높이 여유를 요구한다. */
    public static boolean coolerCaseWithClearance(Cooler cooler, Case pcCase, long clearanceMm) {
        return clearanceMm >= 0 && coolerCase(cooler, pcCase)
                && pcCase.getCoolerLength() - cooler.getCoolerLength() >= clearanceMm;
    }

    /** 파워: 용도별 최소 출력과 GPU의 권장 시스템 출력을 충족해야 한다. */
    public static boolean power(Gpu gpu, Power psu, long baseline) {
        if (psu == null || !positive(psu.getWattage())
                || psu.getWattage() < baseline) {
            return false;
        }
        return gpu == null || (positive(gpu.getRecommendedPower())
                && psu.getWattage() >= gpu.getRecommendedPower());
    }

    /** 케이스의 PSU 호환 규격 데이터가 없으므로 기존대로 ATX만 후보로 취급한다. */
    public static boolean standardAtxPower(Power psu) {
        return psu != null && token(psu.getSize()).equals("ATX");
    }

    /**
     * CPU 내장 그래픽: 현재 DB에는 전용 지원 여부 컬럼이 없으므로
     * 모델명으로 판별 가능한 일부 데스크톱 CPU만 통과시킨다.
     * 실제 상세 스펙과 불일치할 수 있으므로 제조사 CPU SKU 확인 권장.
     */
    public static boolean integratedGraphics(Cpu cpu) {
        if (cpu == null || cpu.getName() == null) {
            return false;
        }
        String name = cpu.getName().toUpperCase(Locale.ROOT);

        if (name.contains("인텔") || name.contains("INTEL")) {
            if (name.contains("제온") || name.contains("XEON")) {
                return false;
            }
            return !INTEL_NO_IGPU.matcher(name).find();
        }
        if (name.contains("라이젠") || name.contains("RYZEN")) {
            if (AMD_NO_IGPU.matcher(name).find()) {
                return false;
            }
            if (AMD_APU.matcher(name).find()) {
                return true;
            }
            return socket(cpu.getSocketType()).equals("AMD5")
                    && AMD_AM5_DESKTOP.matcher(name).find();
        }
        return false;
    }
}
