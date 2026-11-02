package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * 총예산을 유지하면서 여러 부품을 동시에 교환하는 제한 시간 기반 재탐색.
 *
 * 기존 잔액 업그레이드와 다른 점:
 * RAM/SSD/메인보드 등의 기존 선택을 낮은 가격의 호환 제품으로 교체해 확보한
 * 예산으로 CPU/GPU 성능을 향상시키는 조합도 탐색한다.
 *
 * CPU/GPU/RAM/SSD 종합 점수는 호출부 EstimateService의 기존 계산을 재사용한다.
 * 최종 비용과 소켓/RAM/PCIe/장착/파워 조건은 BuildRules로 재검사한다.
 * DB에 없는 전원부/쿨러 TDP 품질은 보장하지 않는다.
 */
public final class BudgetExchangeOptimizer {
    private BudgetExchangeOptimizer() { }

    private static final int CPU_SCORE_LIMIT = 12;
    private static final int CPU_VALUE_LIMIT = 8;
    private static final int GPU_SCORE_LIMIT = 8;
    private static final int GPU_VALUE_LIMIT = 6;
    private static final int RAM_SCORE_LIMIT = 9;
    private static final int SSD_SCORE_LIMIT = 9;
    private static final int CHEAP_LIMIT = 6;
    private static final int BOARDS_PER_TYPE = 3;
    private static final int KEEP_BEST = 8;
    private static final double EPS = 1e-8;

    private static long price(Long value) { return value == null ? 0 : Math.max(0, value); }
    private static double bench(Long value) { return value == null ? 0 : Math.max(0, value); }
    private static double bench(Double value) { return value == null ? 0 : Math.max(0, value); }

    private record Support(Mainboard board, Power power, Case pcCase, Cooler cooler, long price) { }
    private record Ranked(PricePriorityOptimizer.Build build, double score) { }

    /**
     * @param maxMillis 이 견적 1개에 허용할 탐색 시간 (실행 완료 시간은 환경에 따라 조금 다를 수 있음)
     */
    public static PricePriorityOptimizer.Build rebalance(
            PricePriorityOptimizer.Build original,
            long budget,
            MinimumBudgetCalculator.Policy policy,
            MinimumBudgetCalculator.Parts inventory,
            ToDoubleFunction<PricePriorityOptimizer.Build> scoreFunction,
            long maxMillis) {

        if (original == null || inventory == null || policy == null || scoreFunction == null
                || original.totalPrice() > budget || maxMillis <= 0) return original;
        if (!PricePriorityOptimizer.isCompatible(original, policy)) return original;
        long deadline = System.nanoTime() + Math.max(1, maxMillis) * 1_000_000L;

        double originalScore = scoreFunction.applyAsDouble(original);
        // 초기 견적도 기존의 잔액 업그레이드 대상으로 넣는다.
        PricePriorityOptimizer.Build incumbent = PricePriorityOptimizer.upgrade(original, budget, policy, inventory);
        double bestScore = scoreFunction.applyAsDouble(incumbent);
        if (bestScore + EPS < originalScore) {
            incumbent = original;
            bestScore = originalScore;
        }

        List<Cpu> cpus = cpus(original, inventory, policy, budget);
        List<Gpu> gpus = gpus(original, inventory, policy, budget);
        List<Ram> rams = rams(original, inventory, policy, budget);
        List<Ssd> ssds = ssds(original, inventory, policy, budget);
        if (cpus.isEmpty() || gpus.isEmpty() || rams.isEmpty() || ssds.isEmpty()) return incumbent;

        List<Mainboard> boardsByPrice = inventory.mainboards().stream()
                .filter(x -> price(x.getPrice()) > 0).sorted(Comparator.comparingLong(Mainboard::getPrice)).toList();
        List<Power> powersByPrice = inventory.powers().stream()
                .filter(x -> price(x.getPrice()) > 0).sorted(Comparator.comparingLong(Power::getPrice)).toList();
        List<Case> casesByPrice = inventory.cases().stream()
                .filter(x -> price(x.getPrice()) > 0).sorted(Comparator.comparingLong(Case::getPrice)).toList();
        List<Cooler> coolersByPrice = inventory.coolers().stream()
                .filter(x -> price(x.getPrice()) > 0).sorted(Comparator.comparingLong(Cooler::getPrice)).toList();

        List<Ranked> finalists = new ArrayList<>();
        // 너무 비싼 GPU/CPU 조합에는 연산을 쓰지 않는다.
        outer:
        for (Cpu cpu : cpus) {
            if (System.nanoTime() >= deadline) break;
            for (Gpu gpu : gpus) {
                if (System.nanoTime() >= deadline) break outer;
                long basicPrice = price(cpu.getPrice()) + (gpu == null ? 0 : price(gpu.getPrice()));
                if (basicPrice >= budget) continue;

                Power power = null;
                for (Power p : powersByPrice) {
                    if (RecommendationQualityRules.powerSuitable(cpu, gpu, p, policy.minWattage())) {
                        power = p; break;
                    }
                }
                if (power == null) continue;

                // 메인보드별(DDR4/DDR5) 최저가 3개와 원래 보드를 검토한다.
                // 기존의 비싼 메인보드만 고집하지 않아 GPU 업그레이드 비용을 확보할 수 있다.
                List<Mainboard> boards = boards(cpu, gpu, original.mainboard(), boardsByPrice);
                for (Mainboard board : boards) {
                    if (System.nanoTime() >= deadline) break outer;
                    Support support = cheapestSupport(cpu, gpu, board, power,
                            casesByPrice, coolersByPrice, deadline);
                    if (support == null) continue;
                    long fixedCost = basicPrice + support.price();
                    if (fixedCost >= budget) continue;

                    for (Ram ram : rams) {
                        if (!BuildRules.ramBoard(ram, board)) continue;
                        if (fixedCost + price(ram.getPrice()) >= budget) continue;
                        for (Ssd ssd : ssds) {
                            if (System.nanoTime() >= deadline) break outer;
                            long total = fixedCost + price(ram.getPrice()) + price(ssd.getPrice());
                            if (total > budget) continue;
                            PricePriorityOptimizer.Build build = new PricePriorityOptimizer.Build(
                                    cpu, gpu, ram, ssd, board, support.power(), support.pcCase(), support.cooler());
                            if (build.totalPrice() != total || !PricePriorityOptimizer.isCompatible(build, policy)) continue;
                            double value = scoreFunction.applyAsDouble(build);
                            if (!Double.isFinite(value)) continue;
                            // 저가 부대 부품의 일시적 점수 불이익을 고려하여 후보를 여러 개 유지한다.
                            // 코어 조합이 같아도 RAM/SSD가 다르면 서로 다른 후보이다.
                            offer(finalists, new Ranked(build, value));
                        }
                    }
                }
            }
        }

        // 저렴하게 재구성한 후보에 예산을 다시 배분한 다음 실제 최종 점수로 비교한다.
        for (Ranked result : finalists) {
            PricePriorityOptimizer.Build upgraded = PricePriorityOptimizer.upgrade(
                    result.build(), budget, policy, inventory);
            if (upgraded.totalPrice() > budget || !PricePriorityOptimizer.isCompatible(upgraded, policy)) continue;
            double score = scoreFunction.applyAsDouble(upgraded);
            if (score > bestScore + EPS || (Math.abs(score - bestScore) <= EPS
                    && upgraded.totalPrice() > incumbent.totalPrice())) {
                incumbent = upgraded;
                bestScore = score;
            }
        }
        return incumbent;
    }

    private static void offer(List<Ranked> finalists, Ranked candidate) {
        String key = identity(candidate.build());
        for (int i = 0; i < finalists.size(); i++) {
            Ranked old = finalists.get(i);
            if (identity(old.build()).equals(key)) {
                if (candidate.score() > old.score()) finalists.set(i, candidate);
                return;
            }
        }
        if (finalists.size() < KEEP_BEST) { finalists.add(candidate); return; }
        int weakest = 0;
        for (int i = 1; i < finalists.size(); i++) {
            if (finalists.get(i).score() < finalists.get(weakest).score()) weakest = i;
        }
        if (candidate.score() > finalists.get(weakest).score()) finalists.set(weakest, candidate);
    }

    private static String identity(PricePriorityOptimizer.Build b) {
        return b.cpu().getId() + ":" + (b.gpu() == null ? "-" : b.gpu().getId())
                + ":" + b.ram().getId() + ":" + b.ssd().getId();
    }

    private static Support cheapestSupport(Cpu cpu, Gpu gpu, Mainboard board, Power power,
                                            List<Case> cases, List<Cooler> coolers, long deadline) {
        Support cheapest = null;
        // 대개 저가 쿨러/케이스에서 해결된다. 과도한 전수 조합 탐색은 방지한다.
        for (Cooler cooler : coolers) {
            if (!RecommendationQualityRules.coolerSuitable(cpu, cooler)) continue;
            if (cheapest != null && price(cooler.getPrice()) >= cheapest.price()
                    - price(board.getPrice()) - price(power.getPrice())) break;
            if (System.nanoTime() >= deadline) break;
            for (Case pcCase : cases) {
                long combined = price(cooler.getPrice()) + price(pcCase.getPrice());
                if (cheapest != null && combined >= cheapest.price() - price(board.getPrice()) - price(power.getPrice())) break;
                if (BuildRules.boardCase(board, pcCase) && BuildRules.gpuCase(gpu, pcCase)
                        && BuildRules.coolerCase(cooler, pcCase)) {
                    long amount = price(board.getPrice()) + price(power.getPrice()) + combined;
                    if (cheapest == null || amount < cheapest.price()) {
                        cheapest = new Support(board, power, pcCase, cooler, amount);
                    }
                    break; // 케이스가 가격 오름차순이므로 이 쿨러에 대한 가장 저렴한 케이스
                }
            }
        }
        return cheapest;
    }

    private static List<Mainboard> boards(Cpu cpu, Gpu gpu, Mainboard old, List<Mainboard> all) {
        LinkedHashMap<Long, Mainboard> picked = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Mainboard board : all) {
            if (!BuildRules.cpuMainboardBalanced(cpu, board) || !BuildRules.gpuMainboard(gpu, board)) continue;
            String type = board.getMemoryType() == null ? "" : board.getMemoryType().toUpperCase();
            if (counts.getOrDefault(type, 0) >= BOARDS_PER_TYPE) continue;
            counts.put(type, counts.getOrDefault(type, 0) + 1);
            picked.put(board.getId(), board);
        }
        if (old != null && BuildRules.cpuMainboardBalanced(cpu, old)
                && BuildRules.gpuMainboard(gpu, old)) picked.putIfAbsent(old.getId(), old);
        return List.copyOf(picked.values());
    }

    private static <T> List<T> choose(List<T> valid, T current,
                                     Function<T, Long> id,
                                     Comparator<T> byScore,
                                     ToDoubleFunction<T> value,
                                     Comparator<T> byPrice,
                                     int scoreCount, int valueCount) {
        LinkedHashMap<Long, T> selected = new LinkedHashMap<>();
        // 동일 벤치마크 제품이 후보군을 독점하지 않도록 먼저 정렬 후 점수당 1개 선택.
        // 가격 효율 상위 그룹과 가장 저렴한 그룹도 포함한다.
        List<T> ranked = valid.stream().sorted(byScore).toList();
        Map<Double, Integer> scores = new HashMap<>();
        for (T candidate : ranked) {
            double bench = value.applyAsDouble(candidate);
            if (scores.putIfAbsent(bench, 1) != null) continue;
            selected.putIfAbsent(id.apply(candidate), candidate);
            if (scores.size() >= scoreCount) break;
        }
        valid.stream().sorted(Comparator.comparingDouble((T v) -> value.applyAsDouble(v)
                        / Math.max(1, priceValue(v))).reversed())
                .limit(valueCount).forEach(v -> selected.putIfAbsent(id.apply(v), v));
        valid.stream().sorted(byPrice).limit(CHEAP_LIMIT)
                .forEach(v -> selected.putIfAbsent(id.apply(v), v));
        if (current != null) selected.putIfAbsent(id.apply(current), current);
        return new ArrayList<>(selected.values());
    }

    // 이 함수는 아래 각 부품의 비용을 순수 자바 제네릭으로 다루기 위한 보조이며
    // choose 호출부에서 비용 효율 정렬을 다시 수행하도록 단순화한다.
    private static <T> long priceValue(T v) {
        if (v instanceof Cpu c) return price(c.getPrice());
        if (v instanceof Gpu g) return price(g.getPrice());
        if (v instanceof Ram r) return price(r.getPrice());
        if (v instanceof Ssd s) return price(s.getPrice());
        return Long.MAX_VALUE;
    }

    private static List<Cpu> cpus(PricePriorityOptimizer.Build old, MinimumBudgetCalculator.Parts parts,
                                  MinimumBudgetCalculator.Policy policy, long budget) {
        List<Cpu> valid = parts.cpus().stream().filter(c -> price(c.getPrice()) > 0
                && c.getPrice() < budget && bench(c.getBenchScore()) >= policy.cpuBench()
                && (!policy.needsIgpu() || BuildRules.integratedGraphics(c))).toList();
        return choose(valid, old.cpu(), Cpu::getId,
                Comparator.comparingLong((Cpu c) -> c.getBenchScore()).reversed()
                        .thenComparingLong(Cpu::getPrice),
                c -> bench(c.getBenchScore()), Comparator.comparingLong(Cpu::getPrice),
                CPU_SCORE_LIMIT, CPU_VALUE_LIMIT);
    }

    private static List<Gpu> gpus(PricePriorityOptimizer.Build old, MinimumBudgetCalculator.Parts parts,
                                  MinimumBudgetCalculator.Policy policy, long budget) {
        if (old.gpu() == null && !policy.needsGpu()) return java.util.Collections.singletonList(null);
        List<Gpu> valid = parts.gpus().stream().filter(g -> price(g.getPrice()) > 0
                && g.getPrice() < budget && bench(g.getBenchScore()) >= policy.gpuBench()
                && BuildRules.positive(g.getRecommendedPower())).toList();
        return choose(valid, old.gpu(), Gpu::getId,
                Comparator.comparingLong((Gpu g) -> g.getBenchScore()).reversed()
                        .thenComparingLong(Gpu::getPrice),
                g -> bench(g.getBenchScore()), Comparator.comparingLong(Gpu::getPrice),
                GPU_SCORE_LIMIT, GPU_VALUE_LIMIT);
    }

    private static List<Ram> rams(PricePriorityOptimizer.Build old, MinimumBudgetCalculator.Parts parts,
                                  MinimumBudgetCalculator.Policy policy, long budget) {
        List<Ram> valid = parts.rams().stream().filter(r -> price(r.getPrice()) > 0
                && r.getPrice() < budget && r.getCapacity() != null && r.getCapacity() >= policy.ramGb()
                && r.getModuleCount() != null && r.getModuleCount() >= policy.ramModules()
                && r.getBenchScore() != null && r.getBenchScore() > 0).toList();
        List<Ram> picked = choose(valid, old.ram(), Ram::getId,
                Comparator.comparingDouble((Ram r) -> bench(r.getBenchScore())).reversed()
                        .thenComparing(Comparator.comparingLong(Ram::getCapacity).reversed())
                        .thenComparingLong(Ram::getPrice),
                r -> bench(r.getBenchScore()) + Math.min(100, r.getCapacity()) * 0.04,
                Comparator.comparingLong(Ram::getPrice), RAM_SCORE_LIMIT, CHEAP_LIMIT);
        // 성능 점수가 같은 RAM이라도 용량 차이로 점수가 달라질 수 있다.
        Map<String, Ram> perScoreCapacity = new LinkedHashMap<>();
        for (Ram r : valid.stream().sorted(Comparator.comparingLong(Ram::getPrice)).toList()) {
            perScoreCapacity.putIfAbsent(r.getBenchScore() + ":" + r.getCapacity(), r);
        }
        for (Ram r : perScoreCapacity.values()) {
            if (picked.size() >= 22) break;
            if (picked.stream().noneMatch(x -> x.getId().equals(r.getId()))) picked.add(r);
        }
        return picked;
    }

    private static List<Ssd> ssds(PricePriorityOptimizer.Build old, MinimumBudgetCalculator.Parts parts,
                                  MinimumBudgetCalculator.Policy policy, long budget) {
        List<Ssd> valid = parts.ssds().stream().filter(s -> price(s.getPrice()) > 0
                && s.getPrice() < budget && s.getCapacity() != null && s.getCapacity() >= policy.ssdGb()
                && (policy.ssdBench() <= 0 || (s.getBenchScore() != null && s.getBenchScore() >= policy.ssdBench()))
                && s.getBenchScore() != null && s.getBenchScore() > 0).toList();
        List<Ssd> picked = choose(valid, old.ssd(), Ssd::getId,
                Comparator.comparingLong((Ssd s) -> s.getBenchScore()).reversed()
                        .thenComparingLong(Ssd::getPrice),
                s -> bench(s.getBenchScore()) + Math.min(4000, s.getCapacity()) * 3.0,
                Comparator.comparingLong(Ssd::getPrice), SSD_SCORE_LIMIT, CHEAP_LIMIT);
        return picked;
    }
}
