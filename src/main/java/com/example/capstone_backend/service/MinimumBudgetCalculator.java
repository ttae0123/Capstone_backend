package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Price minimization under one of three explicit usage policies. No ratio caps are used. */
public final class MinimumBudgetCalculator {
    private MinimumBudgetCalculator() { }

    // Policy thresholds: project defaults, NOT objectively verified universal PC requirements.
    // Benchmarks use the same PassMark-based values as the product database.
    public record Policy(long cpuBench, long gpuBench, long ramGb, long ssdGb, long ssdBench, long minWattage,
                         boolean needsGpu, boolean needsIgpu, long ramModules) {
        public static Policy of(String usage) {
            return switch (BudgetRatioService.normalizeUsage(usage)) {
                case "OFFICE" -> new Policy(10_000, 0, 16, 500, 0, 450, false, true, 1);
                case "GAMING" -> new Policy(18_000, 19_000, 16, 500, 10_000, 550, true, false, 2);
                case "VIDEO_EDITING" -> new Policy(26_000, 20_000, 32, 1_000, 20_000, 650, true, false, 2);
                default -> throw new IllegalArgumentException("알 수 없는 용도: " + usage);
            };
        }
    }
    public record Parts(List<Cpu> cpus, List<Gpu> gpus, List<Mainboard> mainboards,
                        List<Ram> rams, List<Ssd> ssds, List<Power> powers,
                        List<Case> cases, List<Cooler> coolers) { }
    public record Result(String usage, long amount, Long cpuId, Long gpuId, Long mainboardId,
                         Long ramId, Long ssdId, Long powerId, Long caseId, Long coolerId) { }

    public static Optional<Result> find(String usage, Parts data) {
        String normalizedUsage = BudgetRatioService.normalizeUsage(usage);
        Policy policy = Policy.of(normalizedUsage);
        Comparator<Cpu> cpuOrder = Comparator.comparingLong(Cpu::getPrice);
        List<Cpu> cpus = data.cpus().stream()
                .filter(c -> BuildRules.positive(c.getPrice()) && c.getBenchScore() != null
                        && c.getBenchScore() >= policy.cpuBench()
                        && (!policy.needsIgpu() || BuildRules.integratedGraphics(c)))
                .sorted(cpuOrder).toList();
        List<Gpu> gpus = policy.needsGpu() ? data.gpus().stream()
                .filter(g -> BuildRules.positive(g.getPrice()) && g.getBenchScore() != null
                        && g.getBenchScore() >= policy.gpuBench()
                        && BuildRules.positive(g.getRecommendedPower()))
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
                        && s.getBenchScore() != null && s.getBenchScore() >= policy.ssdBench())
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
        final long lowerTail = ssd.getPrice() + powers.get(0).getPrice()
                + cases.get(0).getPrice() + coolers.get(0).getPrice()
                + (policy.needsGpu() ? gpus.get(0).getPrice() : 0L);
        for (Cpu cpu : cpus) {
            if (cpu.getPrice() + boards.get(0).getPrice() + rams.get(0).getPrice() + lowerTail >= bestPrice) break;
            for (Mainboard board : boards) {
                long cpuBoard = cpu.getPrice() + board.getPrice();
                if (cpuBoard + rams.get(0).getPrice() + lowerTail >= bestPrice) break;
                if (!BuildRules.cpuMainboard(cpu, board)) continue;
                Ram ram = null;
                for (Ram r : rams) {
                    if (BuildRules.ramBoard(r, board)) { ram = r; break; }
                }
                if (ram == null) continue;
                long fixed = cpuBoard + ram.getPrice() + ssd.getPrice();
                if (fixed + powers.get(0).getPrice() + cases.get(0).getPrice()
                        + coolers.get(0).getPrice() + (policy.needsGpu() ? gpus.get(0).getPrice() : 0) >= bestPrice) continue;
                for (Gpu gpu : gpus) {
                    long withGpu = fixed + (gpu == null ? 0L : gpu.getPrice());
                    if (withGpu + powers.get(0).getPrice() + cases.get(0).getPrice() + coolers.get(0).getPrice() >= bestPrice) break;
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
                            break; // cheapest compatible cooler for this case
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
