package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * 예산 비율로 생성한 호환 견적을 총예산 범위에서 개선한다.
 * - CPU/GPU/RAM/SSD: 벤치마크 우선, 동점이면 가격 내림차순
 * - 메인보드/파워/케이스/쿨러: 호환성 통과 후 가격 내림차순
 * - 부품별 비율은 초기 탐색 목표일 뿐, 교체 과정의 절대 가격 상한이 아니다.
 *
 * 가격은 품질/냉각 성능의 보증이 아니다. 원본 DB에 없는 사양을 임의 추정하지 않는다.
 */
public final class PricePriorityOptimizer {
    private PricePriorityOptimizer() { }

    public record Build(Cpu cpu, Gpu gpu, Ram ram, Ssd ssd,
                        Mainboard mainboard, Power power, Case pcCase, Cooler cooler,
                        long totalPrice) {
        public Build(Cpu cpu, Gpu gpu, Ram ram, Ssd ssd,
                     Mainboard mainboard, Power power, Case pcCase, Cooler cooler) {
            this(cpu, gpu, ram, ssd, mainboard, power, pcCase, cooler,
                    price(cpu.getPrice()) + (gpu == null ? 0L : price(gpu.getPrice()))
                    + price(ram.getPrice()) + price(ssd.getPrice())
                    + price(mainboard.getPrice()) + price(power.getPrice())
                    + price(pcCase.getPrice()) + price(cooler.getPrice()));
        }
    }

    private static long price(Long value) {
        return value == null ? 0L : value;
    }

    private static final class Selection {
        Cpu cpu;
        Gpu gpu;
        Ram ram;
        Ssd ssd;
        Mainboard mainboard;
        Power power;
        Case pcCase;
        Cooler cooler;

        Selection(Build build) {
            cpu = build.cpu(); gpu = build.gpu(); ram = build.ram(); ssd = build.ssd();
            mainboard = build.mainboard(); power = build.power(); pcCase = build.pcCase();
            cooler = build.cooler();
        }

        Build snapshot() {
            return new Build(cpu, gpu, ram, ssd, mainboard, power, pcCase, cooler);
        }
    }

    /** 예산 교환 탐색에서도 기존 최종 호환성 검사를 그대로 사용한다. */
    static boolean isCompatible(Build build, MinimumBudgetCalculator.Policy policy) {
        return build != null && compatible(new Selection(build), policy);
    }

    private static boolean compatible(Selection s, MinimumBudgetCalculator.Policy policy) {
        if (s.cpu == null || s.ram == null || s.ssd == null || s.mainboard == null
                || s.power == null || s.pcCase == null || s.cooler == null) return false;
        if (policy.needsGpu() && s.gpu == null) return false;
        if (s.cpu.getBenchScore() == null || s.cpu.getBenchScore() < policy.cpuBench()
                || (policy.needsIgpu() && s.gpu == null && !BuildRules.integratedGraphics(s.cpu))) return false;
        if (s.gpu != null && (s.gpu.getBenchScore() == null
                || s.gpu.getBenchScore() < policy.gpuBench())) return false;
        if (s.ram.getCapacity() == null || s.ram.getCapacity() < policy.ramGb()
                || s.ram.getModuleCount() == null || s.ram.getModuleCount() < policy.ramModules()) return false;
        if (s.ssd.getCapacity() == null || s.ssd.getCapacity() < policy.ssdGb()
                || (policy.ssdBench() > 0 && (s.ssd.getBenchScore() == null
                || s.ssd.getBenchScore() < policy.ssdBench()))) return false;
        return BuildRules.cpuMainboardBalanced(s.cpu, s.mainboard)
                && BuildRules.cpuCooler(s.cpu, s.cooler)
                && BuildRules.ramBoard(s.ram, s.mainboard)
                && BuildRules.gpuMainboard(s.gpu, s.mainboard)
                && BuildRules.boardCase(s.mainboard, s.pcCase)
                && BuildRules.gpuCase(s.gpu, s.pcCase)
                && BuildRules.coolerCase(s.cooler, s.pcCase)
                && BuildRules.standardAtxPower(s.power)
                && BuildRules.power(s.gpu, s.power, policy.minWattage())
                && RecommendationQualityRules.coolerSuitable(s.cpu, s.cooler)
                && RecommendationQualityRules.powerSuitable(s.cpu, s.gpu, s.power, policy.minWattage());
    }

    private static <T> void improveByBenchmark(
            Selection selection, List<T> inventory, Function<Selection, T> current,
            BiConsumer<Selection, T> set, Function<T, Long> getPrice,
            ToDoubleFunction<T> getBenchmark, long allowance,
            long totalBudget, MinimumBudgetCalculator.Policy policy,
            boolean permitSameBenchmark) {
        T old = current.apply(selection);
        if (old == null || allowance < 0) return;
        double oldBenchmark = getBenchmark.applyAsDouble(old);
        long oldPrice = price(getPrice.apply(old));
        long maxPrice = Math.min(totalBudget, oldPrice + allowance);

        List<T> ordered = inventory.stream()
                .filter(item -> price(getPrice.apply(item)) > 0 && price(getPrice.apply(item)) <= maxPrice)
                .filter(item -> permitSameBenchmark
                        ? getBenchmark.applyAsDouble(item) >= oldBenchmark
                        : getBenchmark.applyAsDouble(item) > oldBenchmark)
                .sorted(Comparator.<T>comparingDouble(getBenchmark).reversed()
                        .thenComparing(Comparator.comparingLong((T item) -> price(getPrice.apply(item))).reversed()))
                .toList();
        for (T candidate : ordered) {
            // 동점인 경우에도 기존보다 비싼 상품만 업그레이드 대상으로 본다.
            if (getBenchmark.applyAsDouble(candidate) == oldBenchmark
                    && price(getPrice.apply(candidate)) <= oldPrice) continue;
            set.accept(selection, candidate);
            if (selection.snapshot().totalPrice() <= totalBudget && compatible(selection, policy)) return;
        }
        set.accept(selection, old);
    }

    private static <T> void improveByPrice(
            Selection selection, List<T> inventory, Function<Selection, T> current,
            BiConsumer<Selection, T> set, Function<T, Long> getPrice,
            long allowance, long totalBudget, MinimumBudgetCalculator.Policy policy) {
        T old = current.apply(selection);
        if (old == null || allowance <= 0) return;
        long oldPrice = price(getPrice.apply(old));
        long maxPrice = oldPrice + allowance;
        List<T> ordered = inventory.stream()
                .filter(item -> price(getPrice.apply(item)) > oldPrice
                        && price(getPrice.apply(item)) <= maxPrice)
                .sorted(Comparator.comparingLong((T item) -> price(getPrice.apply(item))).reversed())
                .toList();
        for (T candidate : ordered) {
            set.accept(selection, candidate);
            if (selection.snapshot().totalPrice() <= totalBudget && compatible(selection, policy)) return;
        }
        set.accept(selection, old);
    }

    private static long available(Selection s, long budget) {
        return Math.max(0L, budget - s.snapshot().totalPrice());
    }

    /** 기존 견적을 유지하면서 교체만 수행하므로, 원래 견적보다 총액이 내려가지 않는다. */
    public static Build upgrade(Build original, long budget,
                                MinimumBudgetCalculator.Policy policy,
                                MinimumBudgetCalculator.Parts inventory) {
        if (original == null || original.totalPrice() > budget || inventory == null) return original;
        Selection s = new Selection(original);
        if (!compatible(s, policy)) return original;

        // 성능 향상용으로 잔여 예산의 일부만 사용하여 보조 부품 개선 기회를 남긴다.
        if (s.gpu != null) improveByBenchmark(s, inventory.gpus(), x -> x.gpu, (x, v) -> x.gpu = v,
                Gpu::getPrice, g -> positive(g.getBenchScore()),
                (long) (available(s, budget) * 0.55), budget, policy, false);
        improveByBenchmark(s, inventory.cpus(), x -> x.cpu, (x, v) -> x.cpu = v,
                Cpu::getPrice, c -> positive(c.getBenchScore()),
                (long) (available(s, budget) * 0.55), budget, policy, false);
        improveByBenchmark(s, inventory.rams(), x -> x.ram, (x, v) -> x.ram = v,
                Ram::getPrice, r -> r.getBenchScore() == null ? 0 : r.getBenchScore(),
                (long) (available(s, budget) * 0.55), budget, policy, false);
        improveByBenchmark(s, inventory.ssds(), x -> x.ssd, (x, v) -> x.ssd = v,
                Ssd::getPrice, d -> positive(d.getBenchScore()),
                (long) (available(s, budget) * 0.55), budget, policy, false);

        // 1차: 남은 총예산을 지원 부품 4개로 나눠 최고가 호환 제품 선택.
        long free = available(s, budget);
        improveByPrice(s, inventory.mainboards(), x -> x.mainboard, (x, v) -> x.mainboard = v,
                Mainboard::getPrice, (long) (free * 0.35), budget, policy);
        improveByPrice(s, inventory.coolers(), x -> x.cooler, (x, v) -> x.cooler = v,
                Cooler::getPrice, (long) (free * 0.30), budget, policy);
        improveByPrice(s, inventory.powers(), x -> x.power, (x, v) -> x.power = v,
                Power::getPrice, (long) (free * 0.20), budget, policy);
        improveByPrice(s, inventory.cases(), x -> x.pcCase, (x, v) -> x.pcCase = v,
                Case::getPrice, free - (long) (free * 0.35) - (long) (free * 0.30)
                        - (long) (free * 0.20), budget, policy);

        // 2차: 특정 부품에서 사용하지 못한 몫을 재분배하되, 한 제품의 독식을 방지한다.
        for (int round = 0; round < 2; round++) {
            improveByPrice(s, inventory.coolers(), x -> x.cooler, (x, v) -> x.cooler = v,
                    Cooler::getPrice, available(s, budget) / 2, budget, policy);
            improveByPrice(s, inventory.mainboards(), x -> x.mainboard, (x, v) -> x.mainboard = v,
                    Mainboard::getPrice, available(s, budget) / 2, budget, policy);
            improveByPrice(s, inventory.powers(), x -> x.power, (x, v) -> x.power = v,
                    Power::getPrice, available(s, budget) / 2, budget, policy);
            improveByPrice(s, inventory.cases(), x -> x.pcCase, (x, v) -> x.pcCase = v,
                    Case::getPrice, available(s, budget) / 2, budget, policy);
        }

        // 벤치마크 동점이라면 남은 예산 한도 내에서 더 비싼 모델을 2순위로 평가.
        if (s.gpu != null) improveByBenchmark(s, inventory.gpus(), x -> x.gpu, (x, v) -> x.gpu = v,
                Gpu::getPrice, g -> positive(g.getBenchScore()),
                available(s, budget) / 3, budget, policy, true);
        improveByBenchmark(s, inventory.cpus(), x -> x.cpu, (x, v) -> x.cpu = v,
                Cpu::getPrice, c -> positive(c.getBenchScore()),
                available(s, budget) / 3, budget, policy, true);
        improveByBenchmark(s, inventory.rams(), x -> x.ram, (x, v) -> x.ram = v,
                Ram::getPrice, r -> r.getBenchScore() == null ? 0 : r.getBenchScore(),
                available(s, budget) / 3, budget, policy, true);
        improveByBenchmark(s, inventory.ssds(), x -> x.ssd, (x, v) -> x.ssd = v,
                Ssd::getPrice, d -> positive(d.getBenchScore()),
                available(s, budget) / 3, budget, policy, true);

        Build upgraded = s.snapshot();
        return upgraded.totalPrice() <= budget && compatible(s, policy) ? upgraded : original;
    }

    private static long positive(Long score) {
        return score == null ? 0L : Math.max(0L, score);
    }
}
