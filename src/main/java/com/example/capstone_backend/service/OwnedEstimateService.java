package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.ManualBuildSelection;
import com.example.capstone_backend.dto.OwnedEstimateRequest;
import com.example.capstone_backend.dto.ResponseDTO;
import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;
import com.example.capstone_backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Server-side recommendations with one or more fixed, already-owned DB products.
 * Reuses the same BuildRules, MinimumBudgetCalculator.Policy and DB-derived
 * BudgetRatioService as the ordinary recommendation engine, but charges only
 * NEW parts. This is not a post-processing renaming of an unrelated build.
 *
 * The candidate search is bounded by a 27s deadline and per-category limits,
 * so it is a heuristic search, not a proof of global optimality.
 */
@Service
public class OwnedEstimateService {
    private final CpuRepository cpuRepository;
    private final GpuRepository gpuRepository;
    private final MainboardRepository mainboardRepository;
    private final RamRepository ramRepository;
    private final SsdRepository ssdRepository;
    private final PowerRepository powerRepository;
    private final CaseRepository caseRepository;
    private final CoolerRepository coolerRepository;
    private final BudgetRatioService budgetRatioService;

    public OwnedEstimateService(CpuRepository cpuRepository, GpuRepository gpuRepository,
                                MainboardRepository mainboardRepository, RamRepository ramRepository,
                                SsdRepository ssdRepository, PowerRepository powerRepository,
                                CaseRepository caseRepository, CoolerRepository coolerRepository,
                                BudgetRatioService budgetRatioService) {
        this.cpuRepository = cpuRepository;
        this.gpuRepository = gpuRepository;
        this.mainboardRepository = mainboardRepository;
        this.ramRepository = ramRepository;
        this.ssdRepository = ssdRepository;
        this.powerRepository = powerRepository;
        this.caseRepository = caseRepository;
        this.coolerRepository = coolerRepository;
        this.budgetRatioService = budgetRatioService;
    }

    private static final long DEADLINE_NS = 27_000_000_000L;
    private static final int TOP_N = 3;

    @Transactional(readOnly = true)
    public ResponseDTO.ResultList generate(OwnedEstimateRequest request) {
        if (request == null || request.budget() == null || request.budget() <= 0) {
            throw new IllegalArgumentException("추가 예산은 0원보다 커야 합니다.");
        }
        ManualBuildSelection selected = request.ownedParts();
        if (selected == null || countOwned(selected) == 0) {
            throw new IllegalArgumentException("실제 DB에서 선택한 보유 부품 ID가 최소 1개 필요합니다.");
        }
        // The current front end does not send 'usage'. Use the gaming policy
        // consistently rather than silently accepting an unsupported 'WORK'.
        String usage = request.usage() == null || request.usage().isBlank()
                ? "GAMING" : request.usage().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("GAMING", "OFFICE", "VIDEO_EDITING").contains(usage)) {
            throw new IllegalArgumentException("지원하지 않는 usage: " + request.usage());
        }
        String preferred = request.preference() == null ? ""
                : request.preference().trim().toUpperCase(Locale.ROOT);
        long budget = request.budget();
        long deadline = System.nanoTime() + DEADLINE_NS;

        // findById is category-specific; never accept a productCode or a name as an ID.
        Owned fixed = new Owned(
                find(selected.cpuId(), cpuRepository, "CPU"),
                find(selected.mainboardId(), mainboardRepository, "메인보드"),
                find(selected.ramId(), ramRepository, "RAM"),
                find(selected.gpuId(), gpuRepository, "GPU"),
                find(selected.ssdId(), ssdRepository, "SSD"),
                find(selected.powerId(), powerRepository, "파워"),
                find(selected.caseId(), caseRepository, "케이스"),
                find(selected.coolerId(), coolerRepository, "쿨러")
        );
        if (hasIncompatibleFixedParts(fixed)) {
            throw new OwnedEstimateNotPossibleException("선택한 보유 부품끼리 규격이 호환되지 않습니다.");
        }

        MinimumBudgetCalculator.Policy policy = MinimumBudgetCalculator.Policy.of(usage);
        BudgetRatioService.Allocation ratio = budgetRatioService.resolve(usage, budget);
        double weightSum = 0;
        if (fixed.cpu() == null) weightSum += ratio.cpu();
        if (fixed.gpu() == null) weightSum += ratio.gpu();
        if (fixed.mainboard() == null) weightSum += ratio.mainboard();
        if (fixed.ram() == null) weightSum += ratio.ram();
        if (fixed.ssd() == null) weightSum += ratio.ssd();
        if (fixed.power() == null) weightSum += ratio.power();
        if (fixed.pcCase() == null) weightSum += ratio.pcCase();
        if (fixed.cooler() == null) weightSum += ratio.cooler();
        if (weightSum == 0) weightSum = 1;  // all 8 parts already owned

        double cpuTarget = budget * ratio.cpu() / weightSum;
        double gpuTarget = budget * ratio.gpu() / weightSum;
        double mbTarget = budget * ratio.mainboard() / weightSum;
        double ramTarget = budget * ratio.ram() / weightSum;
        double ssdTarget = budget * ratio.ssd() / weightSum;
        double powerTarget = budget * ratio.power() / weightSum;
        double caseTarget = budget * ratio.pcCase() / weightSum;
        double coolerTarget = budget * ratio.cooler() / weightSum;

        List<Cpu> cpus = shortlist(
                fixed.cpu() == null ? cpuRepository.findAll() : List.of(fixed.cpu()),
                c -> (fixed.cpu() != null || hasPrice(c.getPrice()))
                        && (fixed.cpu() != null || (c.getBenchScore() != null && c.getBenchScore() >= policy.cpuBench()))
                        && (fixed.mainboard() == null || BuildRules.cpuMainboard(c, fixed.mainboard()))
                        && (fixed.cooler() == null || BuildRules.cpuCooler(c, fixed.cooler())),
                Cpu::getPrice, c -> number(c.getBenchScore()), cpuTarget, 14,
                fixed.cpu() != null);
        List<Gpu> gpus = shortlist(
                fixed.gpu() == null ? gpuRepository.findAll() : List.of(fixed.gpu()),
                g -> (fixed.gpu() != null || hasPrice(g.getPrice()))
                        && (fixed.gpu() != null || policy.gpuBench() == 0
                        || (g.getBenchScore() != null && g.getBenchScore() >= policy.gpuBench()))
                        && BuildRules.positive(g.getRecommendedPower())
                        && (fixed.mainboard() == null || BuildRules.gpuMainboard(g, fixed.mainboard()))
                        && (fixed.pcCase() == null || BuildRules.gpuCase(g, fixed.pcCase()))
                        && (fixed.power() == null || BuildRules.power(g, fixed.power(), policy.minWattage())),
                Gpu::getPrice, g -> number(g.getBenchScore()), gpuTarget, 14,
                fixed.gpu() != null);
        List<Ram> rams = shortlist(
                fixed.ram() == null ? ramRepository.findAll() : List.of(fixed.ram()),
                r -> (fixed.ram() != null || hasPrice(r.getPrice()))
                        && (fixed.ram() != null || (number(r.getCapacity()) >= policy.ramGb()
                        && number(r.getModuleCount()) >= policy.ramModules()))
                        && (fixed.mainboard() == null || BuildRules.ramBoard(r, fixed.mainboard())),
                Ram::getPrice,
                r -> number(r.getBenchScore()) / 1000.0 + number(r.getCapacity()) * 450.0
                        + (number(r.getModuleCount()) >= 2 ? 1500 : 0), ramTarget, Integer.MAX_VALUE,
                fixed.ram() != null);
        List<Ssd> ssds = shortlist(
                fixed.ssd() == null ? ssdRepository.findAll() : List.of(fixed.ssd()),
                s -> (fixed.ssd() != null || hasPrice(s.getPrice()))
                        && (fixed.ssd() != null || (number(s.getCapacity()) >= policy.ssdGb()
                        && number(s.getBenchScore()) >= policy.ssdBench())),
                Ssd::getPrice,
                s -> number(s.getBenchScore()) + number(s.getCapacity()) * 10, ssdTarget, 10,
                fixed.ssd() != null);
        List<Mainboard> boards = shortlist(
                fixed.mainboard() == null ? mainboardRepository.findAll() : List.of(fixed.mainboard()),
                b -> (fixed.mainboard() != null || hasPrice(b.getPrice()))
                        && (fixed.cpu() == null || BuildRules.cpuMainboard(fixed.cpu(), b))
                        && (fixed.ram() == null || BuildRules.ramBoard(fixed.ram(), b))
                        && (fixed.gpu() == null || BuildRules.gpuMainboard(fixed.gpu(), b))
                        && (fixed.pcCase() == null || BuildRules.boardCase(b, fixed.pcCase())),
                Mainboard::getPrice, b -> 0.0, mbTarget, Integer.MAX_VALUE,
                fixed.mainboard() != null);
        List<Cooler> coolers = shortlist(
                fixed.cooler() == null ? coolerRepository.findAll() : List.of(fixed.cooler()),
                c -> (fixed.cooler() != null || hasPrice(c.getPrice()))
                        && (fixed.cpu() == null || BuildRules.cpuCooler(fixed.cpu(), c))
                        && (fixed.pcCase() == null || BuildRules.coolerCase(c, fixed.pcCase())),
                Cooler::getPrice, c -> 0.0, coolerTarget, Integer.MAX_VALUE,
                fixed.cooler() != null);
        List<Power> powers = shortlist(
                fixed.power() == null ? powerRepository.findAll() : List.of(fixed.power()),
                p -> (fixed.power() != null || hasPrice(p.getPrice()))
                        && BuildRules.standardAtxPower(p)
                        && BuildRules.power(fixed.gpu(), p, policy.minWattage()),
                Power::getPrice, p -> number(p.getWattage()), powerTarget, Integer.MAX_VALUE,
                fixed.power() != null);
        List<Case> cases = shortlist(
                fixed.pcCase() == null ? caseRepository.findAll() : List.of(fixed.pcCase()),
                c -> (fixed.pcCase() != null || hasPrice(c.getPrice()))
                        && (fixed.mainboard() == null || BuildRules.boardCase(fixed.mainboard(), c))
                        && (fixed.gpu() == null || BuildRules.gpuCase(fixed.gpu(), c))
                        && (fixed.cooler() == null || BuildRules.coolerCase(fixed.cooler(), c)),
                Case::getPrice, c -> 0.0, caseTarget, Integer.MAX_VALUE,
                fixed.pcCase() != null);

        if (cpus.isEmpty() || gpus.isEmpty() || boards.isEmpty() || rams.isEmpty()
                || ssds.isEmpty() || powers.isEmpty() || cases.isEmpty() || coolers.isEmpty()) {
            throw new OwnedEstimateNotPossibleException("보유 부품과 호환되는 구매 후보가 없습니다. 보유 부품 또는 용도를 확인해 주세요.");
        }
        // A lower bound on cash spend (not an ordinary all-new-PC minimum budget).
        long minimumCash = minimum(cpus, fixed.cpu(), Cpu::getPrice)
                + minimum(gpus, fixed.gpu(), Gpu::getPrice)
                + minimum(boards, fixed.mainboard(), Mainboard::getPrice)
                + minimum(rams, fixed.ram(), Ram::getPrice)
                + minimum(ssds, fixed.ssd(), Ssd::getPrice)
                + minimum(powers, fixed.power(), Power::getPrice)
                + minimum(cases, fixed.pcCase(), Case::getPrice)
                + minimum(coolers, fixed.cooler(), Cooler::getPrice);
        if (minimumCash > budget) {
            throw new OwnedEstimateNotPossibleException("추가 예산이 부족합니다. 현재 후보의 최소 구매 합계는 약 "
                    + String.format("%,d", minimumCash) + "원 이상입니다.");
        }

        // Re-rank contextually to prevent a global motherboard shortlist from
        // dropping every board for a fixed/less common CPU socket.
        List<Build> best = new ArrayList<>();
        boolean timedOut = false;
        Map<Long, List<Mainboard>> boardCache = new HashMap<>();
        Map<Long, List<Cooler>> coolerCache = new HashMap<>();
        Map<Long, List<Ram>> ramCache = new HashMap<>();
        Map<Long, List<Gpu>> gpuCache = new HashMap<>();
        Map<Long, List<Power>> powerCache = new HashMap<>();
        Map<String, List<Case>> caseCache = new HashMap<>();
        search:
        for (Cpu cpu : cpus) {
            if (System.nanoTime() > deadline) { timedOut = true; break; }
            long cashCpu = cash(cpu, fixed.cpu(), Cpu::getPrice);
            if (cashCpu > budget) continue;
            List<Mainboard> compatibleBoards = boardCache.computeIfAbsent(cpu.getId(), ignored -> choose(boards,
                    b -> BuildRules.cpuMainboard(cpu, b)
                            && (fixed.pcCase() == null || BuildRules.boardCase(b, fixed.pcCase()))
                            && (fixed.ram() == null || BuildRules.ramBoard(fixed.ram(), b))
                            && (fixed.gpu() == null || BuildRules.gpuMainboard(fixed.gpu(), b)),
                    Mainboard::getPrice, b -> 0.0, mbTarget, 14, fixed.mainboard() != null));
            List<Cooler> compatibleCoolers = coolerCache.computeIfAbsent(cpu.getId(), ignored -> choose(coolers,
                    c -> BuildRules.cpuCooler(cpu, c), Cooler::getPrice,
                    c -> 0.0, coolerTarget, 8, fixed.cooler() != null));
            for (Mainboard board : compatibleBoards) {
                if (System.nanoTime() > deadline) { timedOut = true; break search; }
                long cashBoard = cashCpu + cash(board, fixed.mainboard(), Mainboard::getPrice);
                if (cashBoard > budget) continue;
                List<Ram> compatibleRam = ramCache.computeIfAbsent(board.getId(), ignored -> choose(rams,
                        r -> BuildRules.ramBoard(r, board), Ram::getPrice,
                        r -> number(r.getBenchScore()) / 1000.0 + number(r.getCapacity()) * 450,
                        ramTarget, 8, fixed.ram() != null));
                List<Gpu> compatibleGpu = gpuCache.computeIfAbsent(board.getId(), ignored -> choose(gpus,
                        g -> BuildRules.gpuMainboard(g, board), Gpu::getPrice,
                        g -> number(g.getBenchScore()), gpuTarget, 12, fixed.gpu() != null));
                for (Cooler cooler : compatibleCoolers) {
                    long cashCooler = cashBoard + cash(cooler, fixed.cooler(), Cooler::getPrice);
                    if (cashCooler > budget) continue;
                    for (Ram ram : compatibleRam) {
                        long cashRam = cashCooler + cash(ram, fixed.ram(), Ram::getPrice);
                        if (cashRam > budget) continue;
                        for (Gpu gpu : compatibleGpu) {
                            if (System.nanoTime() > deadline) { timedOut = true; break search; }
                            long cashGpu = cashRam + cash(gpu, fixed.gpu(), Gpu::getPrice);
                            if (cashGpu > budget) continue;
                            String caseKey = board.getId() + "/" + gpu.getId() + "/" + cooler.getId();
                            List<Case> compatibleCases = caseCache.computeIfAbsent(caseKey, ignored -> choose(cases,
                                    c -> BuildRules.boardCase(board, c)
                                            && BuildRules.gpuCase(gpu, c)
                                            && BuildRules.coolerCase(cooler, c),
                                    Case::getPrice, c -> 0.0, caseTarget, 9,
                                    fixed.pcCase() != null));
                            List<Power> compatiblePowers = powerCache.computeIfAbsent(gpu.getId(), ignored -> choose(powers,
                                    p -> BuildRules.standardAtxPower(p)
                                            && BuildRules.power(gpu, p, policy.minWattage()),
                                    Power::getPrice, p -> number(p.getWattage()),
                                    powerTarget, 7, fixed.power() != null));
                            for (Case pcCase : compatibleCases) {
                                long cashCase = cashGpu + cash(pcCase, fixed.pcCase(), Case::getPrice);
                                if (cashCase > budget) continue;
                                for (Power power : compatiblePowers) {
                                    long cashPower = cashCase + cash(power, fixed.power(), Power::getPrice);
                                    if (cashPower > budget) continue;
                                    for (Ssd ssd : ssds) {
                                        long total = cashPower + cash(ssd, fixed.ssd(), Ssd::getPrice);
                                        if (total > budget) continue;
                                        Build build = new Build(cpu, gpu, board, ram, ssd, power,
                                                pcCase, cooler, total,
                                                score(cpu, gpu, ram, ssd, board, total, budget, usage, preferred));
                                        offer(best, build);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (best.isEmpty()) {
            throw new OwnedEstimateNotPossibleException(timedOut
                    ? "호환 견적 탐색 시간이 초과되었습니다. 예산 또는 보유 부품을 변경해 주세요."
                    : "보유 부품과 호환되면서 추가 예산을 만족하는 전체 8종 견적이 없습니다.");
        }
        best.sort(Comparator.comparingDouble(Build::score).reversed()
                .thenComparingLong(Build::cash));
        List<ResponseDTO.Recommendation> recommendations = new ArrayList<>();
        for (int i = 0; i < best.size(); i++) {
            Build b = best.get(i);
            List<ResponseDTO.PartDetail> parts = List.of(
                    part("CPU", fixed.cpu() != null, b.cpu().getId(), b.cpu().getName(),
                            b.cpu().getPrice(), b.cpu().getBrand(), null, b.cpu().getBenchScore(),
                            "소켓 " + text(b.cpu().getSocketType()), b.cpu().getProductCode(), b.cpu().getProductUrl()),
                    part("GPU", fixed.gpu() != null, b.gpu().getId(), b.gpu().getName(),
                            b.gpu().getPrice(), b.gpu().getBrand(), b.gpu().getChipsetBrand(), b.gpu().getBenchScore(),
                            "권장파워 " + number(b.gpu().getRecommendedPower()) + "W", b.gpu().getProductCode(), b.gpu().getProductUrl()),
                    part("MAINBOARD", fixed.mainboard() != null, b.board().getId(), b.board().getName(),
                            b.board().getPrice(), b.board().getBrand(), null, null,
                            "소켓 " + text(b.board().getSocketType()) + " / " + text(b.board().getMemoryType()), b.board().getProductCode(), b.board().getProductUrl()),
                    part("RAM", fixed.ram() != null, b.ram().getId(), b.ram().getName(),
                            b.ram().getPrice(), null, null, b.ram().getBenchScore(),
                            text(b.ram().getMemoryType()) + " " + number(b.ram().getCapacity()) + "GB", b.ram().getProductCode(), b.ram().getProductUrl()),
                    part("SSD", fixed.ssd() != null, b.ssd().getId(), b.ssd().getName(),
                            b.ssd().getPrice(), null, null, b.ssd().getBenchScore(),
                            number(b.ssd().getCapacity()) + "GB", b.ssd().getProductCode(), b.ssd().getProductUrl()),
                    part("POWER", fixed.power() != null, b.power().getId(), b.power().getName(),
                            b.power().getPrice(), null, null, null,
                            number(b.power().getWattage()) + "W", b.power().getProductCode(), b.power().getProductUrl()),
                    part("CASE", fixed.pcCase() != null, b.pcCase().getId(), b.pcCase().getName(),
                            b.pcCase().getPrice(), null, null, null,
                            "폼팩터 " + text(b.pcCase().getSize()), b.pcCase().getProductCode(), b.pcCase().getProductUrl()),
                    part("COOLER", fixed.cooler() != null, b.cooler().getId(), b.cooler().getName(),
                            b.cooler().getPrice(), null, null, null,
                            "지원 소켓 " + text(b.cooler().getSocketType()), b.cooler().getProductCode(), b.cooler().getProductUrl())
            );
            long verified = parts.stream().mapToLong(ResponseDTO.PartDetail::price).sum();
            if (verified != b.cash() || verified > budget || parts.size() != 8) {
                throw new IllegalStateException("보유 부품 견적의 금액/품목 검증에 실패했습니다.");
            }
            recommendations.add(new ResponseDTO.Recommendation(
                    "보유 부품 활용 추천 조합 " + (i + 1), verified, parts));
        }
        return new ResponseDTO.ResultList(List.copyOf(recommendations));
    }

    private static <T> T find(Long id, org.springframework.data.jpa.repository.JpaRepository<T, Long> repo,
                              String category) {
        if (id == null) return null;
        if (id <= 0) throw new IllegalArgumentException(category + " 보유 부품 ID는 양수여야 합니다.");
        return repo.findById(id).orElseThrow(() ->
                new IllegalArgumentException(category + " 보유 부품 ID " + id + "가 DB에 없습니다."));
    }
    private static int countOwned(ManualBuildSelection p) {
        return (p.cpuId() != null ? 1 : 0) + (p.mainboardId() != null ? 1 : 0)
                + (p.ramId() != null ? 1 : 0) + (p.gpuId() != null ? 1 : 0)
                + (p.ssdId() != null ? 1 : 0) + (p.powerId() != null ? 1 : 0)
                + (p.caseId() != null ? 1 : 0) + (p.coolerId() != null ? 1 : 0);
    }
    private static boolean hasIncompatibleFixedParts(Owned a) {
        return (a.cpu() != null && a.mainboard() != null && !BuildRules.cpuMainboard(a.cpu(), a.mainboard()))
                || (a.cpu() != null && a.cooler() != null && !BuildRules.cpuCooler(a.cpu(), a.cooler()))
                || (a.ram() != null && a.mainboard() != null && !BuildRules.ramBoard(a.ram(), a.mainboard()))
                || (a.gpu() != null && a.mainboard() != null && !BuildRules.gpuMainboard(a.gpu(), a.mainboard()))
                || (a.mainboard() != null && a.pcCase() != null && !BuildRules.boardCase(a.mainboard(), a.pcCase()))
                || (a.gpu() != null && a.pcCase() != null && !BuildRules.gpuCase(a.gpu(), a.pcCase()))
                || (a.cooler() != null && a.pcCase() != null && !BuildRules.coolerCase(a.cooler(), a.pcCase()))
                || (a.gpu() != null && a.power() != null && !BuildRules.power(a.gpu(), a.power(), 0))
                || (a.power() != null && !BuildRules.standardAtxPower(a.power()));
    }
    private static boolean hasPrice(Long price) { return price != null && price > 0; }
    private static double number(Number n) { return n == null ? 0 : n.doubleValue(); }
    private static String text(String s) { return s == null ? "정보 없음" : s; }

    private static <T> long cash(T value, T fixed, Function<T, Long> price) {
        if (fixed != null) return 0;
        Long actual = price.apply(value);
        if (actual == null || actual < 0) throw new IllegalStateException("신규 부품 가격이 올바르지 않습니다.");
        return actual;
    }
    private static <T> long minimum(List<T> parts, T fixed, Function<T, Long> price) {
        return fixed != null ? 0 : parts.stream().map(price).filter(Objects::nonNull)
                .mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE / 16);
    }
    private static <T> List<T> choose(List<T> pool, Predicate<T> compatible,
            Function<T, Long> price, Function<T, Double> performance,
            double target, int limit, boolean fixed) {
        if (fixed) return pool.stream().filter(compatible).toList();
        return shortlist(pool, compatible, price, performance, target, limit, false);
    }
    private static <T> List<T> shortlist(List<T> input, Predicate<T> predicate,
            Function<T, Long> price, Function<T, Double> performance,
            double target, int limit, boolean fixed) {
        List<T> valid = input.stream().filter(predicate).toList();
        if (fixed || valid.size() <= limit) return valid;
        Comparator<T> priority = Comparator.<T>comparingDouble(
                value -> rank(number(price.apply(value)), performance.apply(value), target))
                .reversed().thenComparingLong(value -> price.apply(value));
        List<T> best = valid.stream().sorted(priority).limit(Math.max(1, limit - 3)).toList();
        List<T> cheap = valid.stream().sorted(Comparator.comparingLong(v -> price.apply(v)))
                .limit(3).toList();
        LinkedHashSet<T> result = new LinkedHashSet<>(best);
        result.addAll(cheap);
        return List.copyOf(result);
    }
    private static double rank(double price, double performance, double target) {
        double targetSafe = Math.max(30_000, target);
        double costRatio = price / targetSafe;
        // Heavy penalties for overspending on a single category, but retain
        // top-value and cheapest products to handle tight budgets.
        return Math.log1p(Math.max(0, performance)) * 2
                - Math.max(0, costRatio - 1) * 8
                - 0.6 * costRatio;
    }
    private static double score(Cpu cpu, Gpu gpu, Ram ram, Ssd ssd,
                                Mainboard board, long cash, long budget,
                                String usage, String preferred) {
        double cpuScore = Math.log1p(number(cpu.getBenchScore()));
        double gpuScore = Math.log1p(number(gpu.getBenchScore()));
        double ramScore = Math.log1p(number(ram.getBenchScore()))
                + Math.log1p(number(ram.getCapacity())) * 0.8;
        double ssdScore = Math.log1p(number(ssd.getBenchScore()))
                + Math.log1p(number(ssd.getCapacity())) * 0.3;
        double performance = switch (usage) {
            case "OFFICE" -> cpuScore * 4.5 + gpuScore * 0.5 + ramScore * 2.5 + ssdScore;
            case "VIDEO_EDITING" -> cpuScore * 3.2 + gpuScore * 2.6 + ramScore * 2.0 + ssdScore * 1.5;
            default -> cpuScore * 2.8 + gpuScore * 4.2 + ramScore * 1.6 + ssdScore;
        };
        double preference = 0;
        if (!preferred.isEmpty()) {
            if (preferred.equalsIgnoreCase(text(cpu.getBrand()))) preference += 2;
            if (preferred.equalsIgnoreCase(text(gpu.getBrand()))
                    || preferred.equalsIgnoreCase(text(gpu.getChipsetBrand()))) preference += 2;
            if (preferred.equalsIgnoreCase(text(board.getBrand()))) preference += 2;
        }
        double savings = budget == 0 ? 0 : (budget - cash) / (double) budget;
        return performance + preference + savings * 0.75;
    }
    private static void offer(List<Build> best, Build candidate) {
        String core = candidate.core();
        for (int i = 0; i < best.size(); i++) {
            Build old = best.get(i);
            if (old.core().equals(core)) {
                if (candidate.score() > old.score()
                        || (candidate.score() == old.score() && candidate.cash() < old.cash())) {
                    best.set(i, candidate);
                    best.sort(Comparator.comparingDouble(Build::score).reversed()
                            .thenComparingLong(Build::cash));
                }
                return;
            }
        }
        best.add(candidate);
        best.sort(Comparator.comparingDouble(Build::score).reversed()
                .thenComparingLong(Build::cash));
        if (best.size() > TOP_N) best.remove(best.size() - 1);
    }
    private static ResponseDTO.PartDetail part(String category, boolean owned,
            Long id, String name, Long originalPrice, String brand, String chipset,
            Number benchmark, String spec, Long productCode, String productUrl) {
        return new ResponseDTO.PartDetail(category,
                owned ? "[보유 중] " + name : name,
                owned ? 0L : originalPrice, brand == null ? "" : brand,
                chipset == null ? "" : chipset, benchmark, spec,
                productCode, productUrl == null ? "" : productUrl, id, owned);
    }
    private record Owned(Cpu cpu, Mainboard mainboard, Ram ram, Gpu gpu,
                         Ssd ssd, Power power, Case pcCase, Cooler cooler) { }
    private record Build(Cpu cpu, Gpu gpu, Mainboard board, Ram ram, Ssd ssd,
                         Power power, Case pcCase, Cooler cooler, long cash, double score) {
        String core() {
            return cpu.getId() + "/" + gpu.getId() + "/" + ram.getId() + "/" + ssd.getId();
        }
    }
}
