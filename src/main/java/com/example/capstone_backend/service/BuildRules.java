
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
 * 현재 DB에 저장된 하드웨어 규격을 바탕으로 호환성을 확인한다.
 *
 * 주의:
 * - 소켓/DDR/길이/폼팩터/권장 전력 등 DB에 존재하는 정보만 판정한다.
 * - BIOS 버전, CPU 지원 목록, RAM 슬롯 수, PSU 커넥터, SSD 인터페이스,
 *   쿨러 냉각 성능은 현재 DB에 없어 이 클래스만으로 확인할 수 없다.
 * - 물리적 호환성 판정과 추천 품질(부품 밸런스)은 분리한다.
 */
public final class BuildRules {

    private static final Pattern PCIE_PATTERN =
            Pattern.compile("^(?:PCIE|PCIEXPRESS)(\\d+)(?:\\.\\d+)?X(\\d+)$");

    private static final Pattern INTEL_NO_IGPU =
            Pattern.compile("\\b\\d{3,5}(?:KF|F)\\b");

    private static final Pattern AMD_NO_IGPU =
            Pattern.compile("\\b\\d{4,5}F\\b");

    private static final Pattern AMD_APU =
            Pattern.compile("\\b\\d{4,5}(?:G|GE|GT)\\b");

    private static final Pattern AMD_AM5_DESKTOP =
            Pattern.compile("\\b(?:7|9)\\d{3}(?:X3D\\d?|X|XT)?\\b");

    private BuildRules() {
    }

    public static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static String token(String value) {
        return value == null ? ""
                : value.toUpperCase(Locale.ROOT)
                  .replaceAll("[^A-Z0-9가-힣]", "");
    }

    private static String socket(String value) {
        String result = token(value)
                .replace("SOCKET", "")
                .replace("소켓", "")
                .replace("지원", "")
                .replace("INTEL", "")
                .replace("인텔", "")
                .replace("LGA", "");

        if (result.equals("AM4") || result.equals("AMD4")) {
            return "AMD4";
        }
        if (result.equals("AM5") || result.equals("AMD5")) {
            return "AMD5";
        }
        return result;
    }

    /**
     * CPU ↔ 메인보드: CPU 소켓이 정확히 같아야 한다.
     * 메인보드 칩셋 등급에 따른 추천 품질 판단은 여기서 하지 않는다.
     */
    public static boolean cpuMainboard(Cpu cpu, Mainboard board) {
        if (cpu == null || board == null) {
            return false;
        }
        String cpuSocket = socket(cpu.getSocketType());
        String boardSocket = socket(board.getSocketType());
        return !cpuSocket.isEmpty() && cpuSocket.equals(boardSocket);
    }

    /**
     * 추천용 밸런스 판단. 실제 장착 가능 여부와 별개이다.
     * 고성능 CPU + 엔트리급 메인보드는 권장하지 않는 것으로 분류한다.
     * 이 결과만으로 하드웨어 비호환이라고 표시해서는 안 된다.
     */
    public static boolean cpuMainboardBalanced(Cpu cpu, Mainboard board) {
        if (!cpuMainboard(cpu, board)) {
            return false;
        }
        if (!isHighPerformanceCpu(cpu)) {
            return true;
        }
        String name = token(board.getName());
        return !(name.contains("H310") || name.contains("H410")
                || name.contains("H510") || name.contains("H610")
                || name.contains("H810") || name.contains("A320")
                || name.contains("A520") || name.contains("A620"));
    }

    private static boolean isHighPerformanceCpu(Cpu cpu) {
        String name = token(cpu.getName());
        return name.contains("코어I7") || name.contains("코어I9")
                || name.contains("COREI7") || name.contains("COREI9")
                || name.contains("ULTRA7") || name.contains("ULTRA9")
                || name.contains("울트라7") || name.contains("울트라9")
                || name.contains("라이젠7") || name.contains("라이젠9")
                || name.contains("RYZEN7") || name.contains("RYZEN9");
    }

    /** CPU ↔ 쿨러: 쉼표 등으로 구분된 지원 소켓 목록을 정확히 비교한다. */
    public static boolean cpuCooler(Cpu cpu, Cooler cooler) {
        if (cpu == null || cooler == null) {
            return false;
        }
        String target = socket(cpu.getSocketType());
        if (target.isEmpty() || cooler.getSocketType() == null) {
            return false;
        }

        for (String raw : cooler.getSocketType().split("[,;/|\\s]+")) {
            String supported = socket(raw);
            if (supported.isEmpty()) {
                continue;
            }
            if (target.equals(supported)) {
                return true;
            }
            // LGA115x 및 LGA1200 쿨러는 같은 고정 홀 패턴을 사용한다.
            // 이는 쿨러 장착 규격에 관한 것이며 CPU-메인보드 소켓 호환과 다르다.
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
     * 메인보드 ↔ RAM: DDR 세대만 필수 일치 조건으로 확인한다.
     * RAM 클럭이 메인보드 표기 최대 클럭보다 높아도 저속 동작이 가능하므로
     * 메모리 클럭 값을 단순 비교하여 호환 불가로 처리하지 않는다.
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
     * GPU ↔ 메인보드: PCIe 세대가 달라도 상호 호환 가능하다.
     * 현재 DB의 메인보드 PCIe 항목은 x16 슬롯이다.
     * 버전은 배제 조건이 아니며, 실제 링크 속도는 낮은 세대에 맞춰질 수 있다.
     */
    public static boolean gpuMainboard(Gpu gpu, Mainboard board) {
        if (board == null) {
            return false;
        }
        if (gpu == null) {  // 외장 GPU 없는 사무용 PC
            return true;
        }
        PcieSpec boardPcie = parsePcie(board.getPcieType());
        PcieSpec gpuPcie = parsePcie(gpu.getPcieType());
        return boardPcie != null && gpuPcie != null
                && boardPcie.lanes() == 16
                && gpuPcie.lanes() > 0 && gpuPcie.lanes() <= 16;
    }

    private static PcieSpec parsePcie(String value) {
        if (value == null) {
            return null;
        }
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
        if (generation < 1 || generation > 7
                || (lanes != 1 && lanes != 2 && lanes != 4
                && lanes != 8 && lanes != 16)) {
            return null;
        }
        return new PcieSpec(generation, lanes);
    }

    private record PcieSpec(int generation, int lanes) {
    }

    /** 메인보드 ↔ 케이스: DB의 폼팩터 상한으로 장착 가능 여부를 판단한다. */
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

    /** 외장 GPU ↔ 케이스: GPU 실측 길이가 케이스 최대 길이 이하여야 한다. */
    public static boolean gpuCase(Gpu gpu, Case pcCase) {
        if (gpu == null) {
            return true;
        }
        return pcCase != null && positive(gpu.getGpuLength())
                && positive(pcCase.getGpuLength())
                && gpu.getGpuLength() <= pcCase.getGpuLength();
    }

    /** CPU 쿨러 ↔ 케이스: 쿨러 높이가 케이스 허용 높이 이하여야 한다. */
    public static boolean coolerCase(Cooler cooler, Case pcCase) {
        return cooler != null && pcCase != null
                && positive(cooler.getCoolerLength())
                && positive(pcCase.getCoolerLength())
                && cooler.getCoolerLength() <= pcCase.getCoolerLength();
    }

    /** 파워: 용도별 최소 출력 및 GPU 제조사 권장 시스템 출력 모두 충족한다. */
    public static boolean power(Gpu gpu, Power psu, long baseline) {
        if (psu == null || !positive(psu.getWattage())
                || psu.getWattage() < baseline) {
            return false;
        }
        return gpu == null || (positive(gpu.getRecommendedPower())
                && psu.getWattage() >= gpu.getRecommendedPower());
    }

    /** 케이스에 파워 장착 지원 규격이 없으므로 ATX만 확정 후보로 취급한다. */
    public static boolean standardAtxPower(Power psu) {
        return psu != null && token(psu.getSize()).equals("ATX");
    }

    /**
     * CPU 내장 그래픽 여부: 현재 DB에는 전용 컬럼이 없으므로
     * 모델명으로 명확히 판별 가능한 데스크톱 CPU만 허용한다.
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
