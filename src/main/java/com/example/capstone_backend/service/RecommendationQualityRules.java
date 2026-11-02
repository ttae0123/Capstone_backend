package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.Cooler;
import com.example.capstone_backend.entity.Cpu;
import com.example.capstone_backend.entity.Gpu;
import com.example.capstone_backend.entity.Power;
import java.util.Locale;

/**
 * 기존 DB 컬럼(name, price, wattage, recommendedPower, coolerLength)을 이용하는
 * 자동 견적용 보수적인 품질 하한. 실측 TDP, PSU 리플/보호회로, 케이스 통풍,
 * GPU 보조전원 커넥터 데이터가 없어 안전성/냉각 성능을 보증하는 판정은 아니다.
 * 불명확한 쿨러는 고발열 CPU용 추천에서 제외한다.
 */
public final class RecommendationQualityRules {
    private RecommendationQualityRules() { }

    private static String key(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9가-힣]", "");
    }

    private static boolean contains(String text, String... parts) {
        for (String part : parts) if (text.contains(part)) return true;
        return false;
    }

    private static long price(Long n) { return n == null ? 0L : n; }

    /** 1=보급형, 2=중간 발열, 3=고발열 CPU. 벤치마크는 발열량이 아니라 보조 힌트다. */
    public static int cpuCoolingTier(Cpu cpu) {
        if (cpu == null) return 3;
        String n = key(cpu.getName());
        long bench = cpu.getBenchScore() == null ? 0 : cpu.getBenchScore();
        if (contains(n, "코어I9", "COREI9", "라이젠9", "RYZEN9",
                "코어울트라9", "COREULTRA9", "울트라9", "ULTRA9") || bench >= 60_000) return 3;
        if (contains(n, "코어I7", "COREI7", "라이젠7", "RYZEN7",
                "코어울트라7", "COREULTRA7", "울트라7", "ULTRA7")
                || (contains(n, "14600K", "14600KF", "13600K", "13600KF", "12600K", "12600KF"))
                || bench >= 35_000) return 2;
        return 1;
    }

    /**
     * 쿨러 명칭에 드러난 일부 알려진 듀얼타워 계열만 고발열용으로 간주한다.
     * 제조사 모델 식별은 휴리스틱이며, 이름이 애매한 제품은 3등급으로 승격하지 않는다.
     * 라디에이터 장착 규격 컬럼이 없어 수랭은 자동으로 상위등급에 포함하지 않는다.
     */
    public static int coolerTier(Cooler cooler) {
        if (cooler == null || price(cooler.getPrice()) <= 0
                || !BuildRules.positive(cooler.getCoolerLength())) return 0;
        String n = key(cooler.getName());
        long height = cooler.getCoolerLength();
        if (height < 120 || contains(n, "LOWPROFILE", "로우프로파일", "TOPFLOW", "로우프로", "IS40", "ALPINE", "CR1300")) return 1;
        if (height >= 150 && contains(n,
                "PEERLESSASSASSIN", "PHANTOMSPIRIT", "AK620", "AG620", "ASSASSINIV",
                "NHD15", "NHD15G2", "DARKROCKPRO", "DARKROCKELITE", "HYPER622",
                "SE207XT", "FROZNA620", "FROZNA720", "HX7280", "PS120SE")) return 3;
        return height >= 145 ? 2 : 1;
    }

    public static boolean coolerSuitable(Cpu cpu, Cooler cooler) {
        if (!BuildRules.cpuCooler(cpu, cooler)) return false;
        int demand = cpuCoolingTier(cpu);
        if (coolerTier(cooler) < demand) return false;
        // 가격은 냉각 성능의 대체 지표가 아니라 명백한 초저가 후보 제외를 위한 보수적 경계다.
        long floor = demand == 3 ? 50_000 : demand == 2 ? 30_000 : 12_000;
        return price(cooler.getPrice()) >= floor;
    }

    /** 제품명에 효율 등급이 명시된 PSU만 채택. 효율 인증은 품질 보증이 아님. */
    public static int powerTier(Power power) {
        if (power == null || !BuildRules.standardAtxPower(power)
                || !BuildRules.positive(power.getWattage()) || price(power.getPrice()) <= 0) return 0;
        String n = key(power.getName());
        if (contains(n, "벌크", "BULK", "OEM", "중고", "리퍼")) return 0;
        boolean hasEfficiencyMark = contains(n, "80PLUS", "ETA", "사이버네틱스", "CYBENETICS");
        if (!hasEfficiencyMark) return 0;
        if (contains(n, "GOLD", "골드", "PLATINUM", "플래티넘", "TITANIUM", "티타늄")) return 3;
        if (contains(n, "SILVER", "실버", "BRONZE", "브론즈")) return 2;
        if (contains(n, "STANDARD", "스탠다드", "WHITE")) return 1;
        return 0;
    }

    public static boolean powerCandidate(Power power) {
        return powerTier(power) >= 1 && price(power.getPrice()) >= 35_000;
    }

    public static boolean powerSuitable(Cpu cpu, Gpu gpu, Power power, long baseline) {
        if (!powerCandidate(power) || !BuildRules.power(gpu, power, baseline)) return false;
        int cpuTier = cpuCoolingTier(cpu);
        long gpuRecommended = gpu == null ? 0L
                : gpu.getRecommendedPower() == null ? Long.MAX_VALUE : gpu.getRecommendedPower();
        long cpuBaseline = cpuTier == 3 ? 650 : cpuTier == 2 ? 550 : 450;
        long gpuMargin = gpu == null ? 0 : cpuTier == 3 ? 100 : cpuTier == 2 ? 50 : 0;
        long required = Math.max(Math.max(baseline, cpuBaseline), gpuRecommended + gpuMargin);
        if (power.getWattage() < required) return false;

        boolean demanding = cpuTier == 3 || gpuRecommended >= 750;
        if (demanding && powerTier(power) < 3) return false; // 골드급 이상의 효율 표기 요구
        if (demanding && price(power.getPrice()) < 80_000) return false;
        if (!demanding && gpu != null && price(power.getPrice()) < 55_000) return false;
        if (gpuRecommended >= 750) {
            String n = key(power.getName());
            if (!contains(n, "ATX30", "ATX31", "PCIE50", "PCIE51")) return false;
        }
        return true;
    }
}
