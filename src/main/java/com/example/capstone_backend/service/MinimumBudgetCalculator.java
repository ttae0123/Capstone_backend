package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 용도별 최소 호환 견적 가격 탐색.
 * HIGH_END: DB의 CPU/GPU 벤치마크 '서로 다른 점수' 상위 10% + RAM >= 32GB + SSD >= 1TB.
 * 물리 호환성은 BuildRules를 재사용. BIOS/TDP/PSU 커넥터 등 DB 미보유 항목은 검증하지 못함.
 */
public final class MinimumBudgetCalculator {
    private MinimumBudgetCalculator() { }

    public record Policy(long cpuBench, long gpuBench, long ramGb, long ssdGb, long ssdBench,
                         long minWattage, boolean needsGpu, boolean needsIgpu,
                         long ramModules, boolean requireBalancedBoard) {
        public static Policy of(String usage) {
            return switch (BudgetRatioService.normalizeUsage(usage)) {
                case "OFFICE" -> new Policy(10_000, 0, 16, 500, 0, 450, false, true, 1, false);
                case "GAMING" -> new Policy(18_000, 19_000, 16, 500, 10_000, 550, true, false, 2, false);
                case "HIGH_END" -> new Policy(0, 0, 32, 1_000, 0, 650, true, false, 1, true);
                default -> throw new IllegalArgumentException("알 수 없는 용도: " + usage);
            };
        }
    }

    public record Parts(List<Cpu> cpus, List<Gpu> gpus, List<Mainboard> mainboards,
                        List<Ram> rams, List<Ssd> ssds, List<Power> powers,
                        List<Case> cases, List<Cooler> coolers) { }

    public record Result(String usage, long amount, Long cpuId, Long gpuId, Long mainboardId,
                         Long ramId, Long ssdId, Long powerId, Long caseId, Long coolerId) { }

    /** 동점 벤치마크에 걸친 여러 제조사 판매상품을 중복 집계하지 않음. */
    private static long topDecileCut(List<Long> values) {
        List<Long> scores = values.stream()
                .filter(score -> score != null && score > 0)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        if (scores.isEmpty()) {
            throw new IllegalStateException("상위 10% 기준을 계산할 유효 벤치마크가 없습니다.");
        }
        int selected = Math.max(1, (scores.size() + 9) / 10);
        return scores.get(selected - 1);
    }

    /** 견적 추천 엔진에서도 이 정책을 사용해야 상위 10% 성능 조건이 유지된다. */
    public static Policy effectivePolicy(String usage, List<Cpu> cpus, List<Gpu> gpus) {
        String normalized = BudgetRatioService.normalizeUsage(usage);
        Policy base = Policy.of(normalized);
        if (!"HIGH_END".equals(normalized)) return base;
        long cpuCut = topDecileCut(cpus.stream().filter(c -> BuildRules.positive(c.getPrice()))
                .map(Cpu::getBenchScore).toList());
        long gpuCut = topDecileCut(gpus.stream().filter(g -> BuildRules.positive(g.getPrice()))
                .map(Gpu::getBenchScore).toList());
        return new Policy(cpuCut, gpuCut, base.ramGb(), base.ssdGb(), base.ssdBench(),
                base.minWattage(), base.needsGpu(), base.needsIgpu(),
                base.ramModules(), base.requireBalancedBoard());
    }

    public static Optional<Result> find(String usage, Parts data) {
        String normalizedUsage = BudgetRatioService.normalizeUsage(usage);
        Policy policy = effectivePolicy(normalizedUsage, data.cpus(), data.gpus());
        long cpuCut = policy.cpuBench();
        long gpuCut = policy.gpuBench();

        List<Cpu> cpus = data.cpus().stream()
                .filter(c -> BuildRules.positive(c.getPrice()) && c.getBenchScore() != null
                        && c.getBenchScore() >= cpuCut
                        && (!policy.needsIgpu() || BuildRules.integratedGraphics(c)))
                .sorted(Comparator.comparingLong(Cpu::getPrice)).toList();
        List<Gpu> gpus = policy.needsGpu() ? data.gpus().stream()
                .filter(g -> BuildRules.positive(g.getPrice()) && g.getBenchScore() != null
                        && g.getBenchScore() >= gpuCut && BuildRules.positive(g.getRecommendedPower()))
                .sorted(Comparator.comparingLong(Gpu::getPrice)).toList()
                : java.util.Collections.singletonList(null);
        List<Mainboard> boards = data.mainboards().stream()
                .filter(b -> BuildRules.positive(b.getPrice()))
                .sorted(Comparator.comparingLong(Mainboard::getPrice)).toList();
        List<Ram> rams = data.rams().stream()
                .filter(r -> BuildRules.positive(r.getPrice()) && r.getCapacity() != null
                        && r.getCapacity() >= policy.ramGb()
                        && r.getModuleCount() != null && r.getModuleCount() >= policy.ramModules())
                .sorted(Comparator.comparingLong(Ram::getPrice)).toList();
        Ssd ssd = data.ssds().stream()
                .filter(s -> BuildRules.positive(s.getPrice()) && s.getCapacity() != null
                        && s.getCapacity() >= policy.ssdGb()
                        && (policy.ssdBench() <= 0 || (s.getBenchScore() != null
                            && s.getBenchScore() >= policy.ssdBench())))
                .min(Comparator.comparingLong(Ssd::getPrice)).orElse(null);
        List<Power> powers = data.powers().stream()
                .filter(p -> BuildRules.positive(p.getPrice()) && BuildRules.standardAtxPower(p)
                        && BuildRules.power(null, p, policy.minWattage()))
                .sorted(Comparator.comparingLong(Power::getPrice)).toList();
        List<Case> cases = data.cases().stream()
                .filter(c -> BuildRules.positive(c.getPrice()))
                .sorted(Comparator.comparingLong(Case::getPrice)).toList();
        List<Cooler> coolers = data.coolers().stream()
                .filter(c -> BuildRules.positive(c.getPrice()))
                .sorted(Comparator.comparingLong(Cooler::getPrice)).toList();

        if (cpus.isEmpty() || gpus.isEmpty() || boards.isEmpty() || rams.isEmpty()
                || ssd == null || powers.isEmpty() || cases.isEmpty() || coolers.isEmpty()) {
            return Optional.empty();
        }

        Result best = null;
        long bestPrice = Long.MAX_VALUE;
        long lowerTail = ssd.getPrice() + powers.get(0).getPrice()
                + cases.get(0).getPrice() + coolers.get(0).getPrice()
                + (policy.needsGpu() ? gpus.get(0).getPrice() : 0L);

        for (Cpu cpu : cpus) {
            if (cpu.getPrice() + boards.get(0).getPrice() + rams.get(0).getPrice() + lowerTail >= bestPrice) break;
            for (Mainboard board : boards) {
                long cpuBoard = cpu.getPrice() + board.getPrice();
                if (cpuBoard + rams.get(0).getPrice() + lowerTail >= bestPrice) break;
                if (!BuildRules.cpuMainboard(cpu, board)) continue;
                if (policy.requireBalancedBoard() && !BuildRules.cpuMainboardBalanced(cpu, board)) continue;
                Ram ram = null;
                for (Ram r : rams) {
                    if (BuildRules.ramBoard(r, board)) { ram = r; break; }
                }
                if (ram == null) continue;
                long fixed = cpuBoard + ram.getPrice() + ssd.getPrice();
                if (fixed + powers.get(0).getPrice() + cases.get(0).getPrice()
                        + coolers.get(0).getPrice()
                        + (policy.needsGpu() ? gpus.get(0).getPrice() : 0) >= bestPrice) continue;
                for (Gpu gpu : gpus) {
                    long withGpu = fixed + (gpu == null ? 0L : gpu.getPrice());
                    if (withGpu + powers.get(0).getPrice() + cases.get(0).getPrice() + coolers.get(0).getPrice() >= bestPrice) break;
                    if (!BuildRules.gpuMainboard(gpu, board)) continue;
                    Power power = null;
                    for (Power p : powers) {
                        if (BuildRules.power(gpu, p, policy.minWattage())) { power = p; break; }
                    }
                    if (power == null) continue;
                    long fixedWithPower = withGpu + power.getPrice();
                    for (Case pcCase : cases) {
                        if (fixedWithPower + pcCase.getPrice() + coolers.get(0).getPrice() >= bestPrice) break;
                        if (!BuildRules.boardCase(board, pcCase) || !BuildRules.gpuCase(gpu, pcCase)) continue;
                        for (Cooler cooler : coolers) {
                            long amount = fixedWithPower + pcCase.getPrice() + cooler.getPrice();
                            if (amount >= bestPrice) break;
                            if (!BuildRules.cpuCooler(cpu, cooler) || !BuildRules.coolerCase(cooler, pcCase)) continue;
                            bestPrice = amount;
                            best = new Result(normalizedUsage, amount, cpu.getId(),
                                    gpu == null ? null : gpu.getId(), board.getId(), ram.getId(), ssd.getId(),
                                    power.getId(), pcCase.getId(), cooler.getId());
                            break;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
