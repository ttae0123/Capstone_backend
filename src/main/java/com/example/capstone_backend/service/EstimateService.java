package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.RequestDTO;
import com.example.capstone_backend.dto.ResponseDTO;
import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.function.Predicate;

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
    private final CoolerRepository coolerRepository;
    private final BudgetRatioService budgetRatioService;
    private final MinimumBudgetService minimumBudgetService;

    // 실행 중인 코드 버전을 서버 로그에서 바로 확인한다.
    private static final String ESTIMATE_BUILD_VERSION = "20261102-DATA-OPTIMIZED-1";
    private static final int MAX_RECOMMENDATION_COUNT = 3;
    private static final int STRATEGY_REBALANCE_ATTEMPTS = 4;
    // 전략별 업그레이드가 더 높은 CPU/GPU를 찾을 여유를 확보한다.
    private static final long STRATEGY_REBALANCE_MS = 900L;
    // 고가 CPU/GPU도 총액이 허용한다면 후보에 포함한다. DB 배분 비율 자체는 변경하지 않는다.
    private static final double CPU_FOCUS_MAX_TOTAL_SHARE = 0.45;
    private static final double GPU_FOCUS_MAX_TOTAL_SHARE = 0.74;
    private enum RecommendationStrategy {
        BALANCED, GPU_FOCUSED, CPU_FOCUSED
    }

    /** 개인용 PC의 게임/사무 견적에 서버 전용 CPU가 포함되는 현상을 방지. */
    private boolean isTargetUsageCpu(Cpu cpu, String usage) {
        if (cpu == null) return false;
        if (!"GAMING".equals(usage) && !"OFFICE".equals(usage)) return true;
        String name = normalize(cpu.getName());
        return !hasAnyToken(name, "EPYC", "XEON", "THREADRIPPER");
    }
    private static final int SEARCH_TOP_POOL_COUNT = 12;
    private static final long RECOMMENDATION_TIMEOUT_MS = 60_000L;

    /*
     * DB의 목표 예산은 고정 상한이 아니라 권장 배분 비율이다.
     * 일부 카테고리에 예산을 유연하게 넘겨도 최종 합계는 총예산 이하로 검사한다.
     * 최소 성능 조건에 맞는 후보가 없을 때는 기존 완화 탐색을 유지한다.
     */
    private static final double CPU_BUDGET_MAX_MULTIPLIER = 1.12;
    private static final double GPU_BUDGET_MAX_MULTIPLIER = 1.12;
    private static final int SEARCH_CPU_LIMIT = 34;
    private static final int SEARCH_GPU_LIMIT = 14;
    private static final int SEARCH_BOARD_LIMIT = 10;
    private static final int SEARCH_RAM_LIMIT = 8;
    private static final int SEARCH_SSD_LIMIT = 8;
    private static final int SUPPORT_COOLER_LIMIT = 6;
    private static final int SUPPORT_POWER_LIMIT = 12;
    private static final int SUPPORT_CASE_LIMIT = 12;

    /*
     * RAM 내부 점수 비율
     */
    private static final double RAM_BENCHMARK_WEIGHT = 0.50;
    private static final double RAM_CAPACITY_WEIGHT = 0.25;
    private static final double RAM_MODULE_WEIGHT = 0.25;

    /*
     * 브랜드 선호도
     *
     * - 후보 정렬에서는 성능/가격을 크게 흔들지 않도록 5%만 우대
     * - 최종 조합 점수에서는 CPU / GPU / Mainboard 각각 최대 +2점
     * - GPU는 제조사(ASUS/MSI/GIGABYTE 등)와 칩셋(NVIDIA/AMD/INTEL)
     *   중 하나 이상이 선호 목록과 일치하면 GPU 1회만 가산한다.
     */
    private static final double BRAND_CANDIDATE_BONUS_RATE = 0.05;
    private static final double BRAND_MATCH_SCORE_BONUS = 2.0;

    public ResponseDTO.ResultList generateRecommendations(
            RequestDTO.EstimateRequest request
    ) {

        System.out.println();
        System.out.println("======================================");
        System.out.println("[EstimateService] 추천 요청 수신");
        System.out.println("[EstimateService] BUILD_VERSION = " + ESTIMATE_BUILD_VERSION);
        System.out.println("======================================");

        if (request == null) {
            throw new IllegalArgumentException("요청 데이터가 비어 있습니다.");
        }

        Long budget = request.budget();

        String usage = BudgetRatioService.normalizeUsage(request.usage());

        Set<String> preferredBrands =
                normalizePreferredBrands(
                        request.brands()
                );

        System.out.println("[REQUEST] budget = " + budget);
        System.out.println("[REQUEST] usage = " + usage);
        System.out.println("[REQUEST] brands = " + preferredBrands);

        if (budget == null || budget <= 0) {
            throw new IllegalArgumentException("budget 값이 올바르지 않습니다.");
        }

        // 용도 선택 후 해당 용도의 허용 예산 범위만 견적 요청 가능
        minimumBudgetService.validateBudget(usage, budget);

        BudgetRatioService.Allocation ratio = budgetRatioService.resolve(usage, budget);
        boolean officeWithoutDiscreteGpu = "OFFICE".equals(usage) && ratio.gpu() == 0.0;
        System.out.println("[BudgetRatio] 기준 예산 = " + ratio.referenceBudget());
        System.out.println("[BudgetRatio] 1차 목표 예산 + 벤치마크/가격 우선 재배분");

        /*
         * ==========================================
         * DB 조회
         * ==========================================
         */

        System.out.println();
        System.out.println("======================================");
        System.out.println("[DB] 부품 데이터 조회 시작");
        System.out.println("======================================");

        // 게이밍·사무용 견적은 서버/워크스테이션 전용 CPU를 제외한다.
        // HIGH_END는 전문가 작업 수요가 있어 기존 후보 정책을 유지한다.
        List<Cpu> cpus = cpuRepository.findAll().stream()
                .filter(cpu -> isTargetUsageCpu(cpu, usage))
                .toList();
        List<Gpu> gpus = gpuRepository.findAll();
        // HIGH_END는 현재 DB의 유효 판매 CPU/GPU 벤치마크 상위 10%를 매 요청에 적용
        MinimumBudgetCalculator.Policy minimumPolicy =
                MinimumBudgetCalculator.effectivePolicy(usage, cpus, gpus);
        List<Ram> rams = ramRepository.findAll();
        List<Ssd> ssds = ssdRepository.findAll();
        List<Mainboard> mbs = mainboardRepository.findAll();
        List<Power> powers = powerRepository.findAll();
        List<Case> cases = caseRepository.findAll();
        List<Cooler> coolers = coolerRepository.findAll();

        System.out.println("[DB] CPU 개수 = " + cpus.size());
        System.out.println("[DB] GPU 개수 = " + gpus.size());
        System.out.println("[DB] RAM 개수 = " + rams.size());
        System.out.println("[DB] SSD 개수 = " + ssds.size());
        System.out.println("[DB] 메인보드 개수 = " + mbs.size());
        System.out.println("[DB] 파워 개수 = " + powers.size());
        System.out.println("[DB] 케이스 개수 = " + cases.size());
        System.out.println("[DB] 쿨러 개수 = " + coolers.size());

        if (cpus.isEmpty()
                || (!officeWithoutDiscreteGpu && gpus.isEmpty())
                || rams.isEmpty()
                || ssds.isEmpty()
                || mbs.isEmpty()
                || powers.isEmpty()
                || cases.isEmpty()
                || coolers.isEmpty()) {

            System.out.println("[ERROR] DB에 필요한 부품 데이터가 부족합니다.");

            return new ResponseDTO.ResultList(List.of());
        }

        /*
         * ==========================================
         * 부품별 목표 예산
         * ==========================================
         */

        long cpuBudget = Math.round(budget * ratio.cpu());
        long gpuBudget = Math.round(budget * ratio.gpu());
        long ramBudget = Math.round(budget * ratio.ram());
        long ssdBudget = Math.round(budget * ratio.ssd());
        long mbBudget = Math.round(budget * ratio.mainboard());
        long powerBudget = Math.round(budget * ratio.power());
        long caseBudget = Math.round(budget * ratio.pcCase());
        long coolerBudget = Math.round(budget * ratio.cooler());

        System.out.println();
        System.out.println("======================================");
        System.out.println("[BudgetRatio] 용도별 예산 분배");
        System.out.println("======================================");

        System.out.println("[CPU] " + cpuBudget);
        System.out.println("[GPU] " + gpuBudget);
        System.out.println("[RAM] " + ramBudget);
        System.out.println("[SSD] " + ssdBudget);
        System.out.println("[MB] " + mbBudget);
        System.out.println("[POWER] " + powerBudget);
        System.out.println("[CASE] " + caseBudget);
        System.out.println("[COOLER] " + coolerBudget);

        // 기존 DB 비율은 균형형의 기준으로 유지한다.
        // CPU/GPU 특화 전략만 놓치지 않도록 전체 예산 기반으로 후보 상한을 완화한다.
        long cpuCandidateCeiling = Math.max((long) (cpuBudget * CPU_BUDGET_MAX_MULTIPLIER),
                officeWithoutDiscreteGpu ? (long) (budget * 0.60) : (long) (budget * CPU_FOCUS_MAX_TOTAL_SHARE));
        long gpuCandidateCeiling = Math.max((long) (gpuBudget * GPU_BUDGET_MAX_MULTIPLIER),
                officeWithoutDiscreteGpu ? 0L : (long) (budget * GPU_FOCUS_MAX_TOTAL_SHARE));
        System.out.println("[CPU MAX CANDIDATE PRICE] " + cpuCandidateCeiling);
        System.out.println("[GPU MAX CANDIDATE PRICE] " + gpuCandidateCeiling);
        System.out.println("[Strategy] DB 배분은 유지 / CPU·GPU 특화 후보 가격 상한만 확장");

        /*
         * ==========================================
         * CPU 후보
         * ==========================================
         */

        List<Cpu> cpuCandidates = cpus.stream()
                .filter(cpu -> hasValidPrice(cpu.getPrice()))
                .filter(cpu -> cpu.getBenchScore() != null && cpu.getBenchScore() >= minimumPolicy.cpuBench())
                .filter(cpu -> !officeWithoutDiscreteGpu || hasIntegratedGraphics(cpu))
                .filter(cpu -> cpu.getPrice() <= cpuCandidateCeiling)
                .sorted(Comparator.comparingLong((Cpu cpu) -> getCpuBenchmarkScore(cpu)).reversed()
                        .thenComparingLong(Cpu::getPrice))
                .toList();

        // 예산 비율 제한으로 전부 탈락한 경우에만 최소 성능 기준을 유지하고 가격 제한을 완화한다.
        cpuCandidates = relaxBudgetLimitWhenEmpty(
                "CPU", cpuCandidates, cpus,
                cpu -> cpu.getBenchScore() != null
                        && cpu.getBenchScore() >= minimumPolicy.cpuBench()
                        && (!officeWithoutDiscreteGpu || hasIntegratedGraphics(cpu)),
                Cpu::getPrice, 30
        );

        /*
         * ==========================================
         * GPU 후보
         * ==========================================
         */

        List<Gpu> gpuCandidates = (officeWithoutDiscreteGpu ? List.<Gpu>of() : gpus).stream()
                .filter(gpu -> hasValidPrice(gpu.getPrice()))
                .filter(gpu -> gpu.getBenchScore() != null && gpu.getBenchScore() >= minimumPolicy.gpuBench())
                .filter(gpu -> BuildRules.positive(gpu.getRecommendedPower()))
                .filter(gpu -> gpu.getPrice() <= gpuCandidateCeiling)
                .sorted(Comparator.comparingLong((Gpu gpu) -> getGpuBenchmarkScore(gpu)).reversed()
                        // 벤치마크 동점이면 값비싼 제조사 모델보다 저렴한 모델을 먼저 선택
                        .thenComparingLong(Gpu::getPrice))
                .toList();

        if (!officeWithoutDiscreteGpu) {
            gpuCandidates = relaxBudgetLimitWhenEmpty(
                    "GPU", gpuCandidates, gpus,
                    gpu -> gpu.getBenchScore() != null
                            && gpu.getBenchScore() >= minimumPolicy.gpuBench()
                            && BuildRules.positive(gpu.getRecommendedPower()),
                    Gpu::getPrice, 30
            );
        }

        /*
         * ==========================================
         * RAM Pool
         *
         * 기존:
         * DDR4 / DDR5를 구분하기 전에
         * 전체 RAM 중 점수 상위 30개를 잘랐음.
         *
         * 수정:
         * 1) RAM은 우선 가격 조건만 적용해 Pool로 유지
         * 2) 메인보드 후보가 정해진 뒤
         * 3) 각 메인보드의 memory_type과 호환되는 RAM만 추출
         * 4) 호환 RAM 안에서 점수를 다시 계산하여 최대 30개 선택
         *
         * 따라서 DDR5 메인보드는 DDR5끼리,
         * DDR4 메인보드는 DDR4끼리 후보 경쟁을 한다.
         * ==========================================
         */

        List<Ram> ramPool = rams.stream()
                .filter(ram -> hasValidPrice(ram.getPrice()))
                .filter(ram -> ram.getCapacity() != null && ram.getCapacity() >= minimumPolicy.ramGb())
                .filter(ram -> ram.getModuleCount() != null && ram.getModuleCount() >= minimumPolicy.ramModules())
                .filter(ram -> ram.getPrice() <= ramBudget * 1.25)
                .toList();

        ramPool = relaxBudgetLimitWhenEmpty(
                "RAM", ramPool, rams,
                ram -> ram.getCapacity() != null && ram.getCapacity() >= minimumPolicy.ramGb()
                        && ram.getModuleCount() != null && ram.getModuleCount() >= minimumPolicy.ramModules(),
                Ram::getPrice, 100
        );

        /*
         * ==========================================
         * SSD 후보
         *
         * 최소 용량 필터 없음
         *
         * 기존 로직 유지
         * ==========================================
         */

        List<Ssd> ssdCandidates = ssds.stream()
                .filter(ssd -> hasValidPrice(ssd.getPrice()))
                .filter(ssd -> ssd.getCapacity() != null && ssd.getCapacity() >= minimumPolicy.ssdGb())
                .filter(ssd -> minimumPolicy.ssdBench() <= 0 ||
                        (ssd.getBenchScore() != null && ssd.getBenchScore() >= minimumPolicy.ssdBench()))
                .filter(ssd -> ssd.getPrice() <= ssdBudget * 1.30)
                .sorted(Comparator.comparingLong((Ssd item) -> getSsdBenchmarkScore(item)).reversed()
                        .thenComparingLong(Ssd::getPrice))
                // 후속 탐색에서 고성능/저가격/다양한 용량을 따로 뽑는다.
                // 여기서 고득점 80개만 남기면 저가 SSD가 다시 사라진다.
                .toList();

        ssdCandidates = relaxBudgetLimitWhenEmpty(
                "SSD", ssdCandidates, ssds,
                ssd -> ssd.getCapacity() != null && ssd.getCapacity() >= minimumPolicy.ssdGb()
                        && (minimumPolicy.ssdBench() <= 0 ||
                        (ssd.getBenchScore() != null && ssd.getBenchScore() >= minimumPolicy.ssdBench())),
                Ssd::getPrice, 30
        );

        /*
         * ==========================================
         * Mainboard
         *
         * 기존:
         * 전체 메인보드 중 저가순 30개만 후보로 사용
         * -> i7/i9 같은 고성능 CPU에도 H610 같은
         *    엔트리 칩셋이 먼저 선택될 수 있었음
         *
         * 수정:
         * 1) 예산 안의 메인보드 전체를 Pool로 구성
         * 2) CPU별로 소켓 호환 후보를 별도 생성
         * 3) 고성능 CPU에는 엔트리 칩셋을 추천 후보에서 제외
         * 4) 지원 사양이 높은 후보를 우선하여 최대 30개 사용
         *
         * 물리적 호환성 체크와는 별개로
         * 추천 품질을 위한 구성 적합성 필터이다.
         * ==========================================
         */

        List<Mainboard> mainboardPool = mbs.stream()
                .filter(mb -> hasValidPrice(mb.getPrice()))
                .filter(mb -> mb.getPrice() <= mbBudget * 1.45)
                .toList();

        mainboardPool = relaxBudgetLimitWhenEmpty(
                "MAINBOARD", mainboardPool, mbs,
                mb -> true, Mainboard::getPrice, 100
        );

        Map<Long, List<Mainboard>> mainboardCandidatesByCpu =
                new HashMap<>();

        for (Cpu cpu : cpuCandidates) {

            mainboardCandidatesByCpu.put(
                    cpu.getId(),
                    getMainboardCandidatesForCpu(
                            cpu,
                            mainboardPool,
                            preferredBrands
                    )
            );
        }

        boolean hasAnyMainboardCandidate =
                mainboardCandidatesByCpu.values()
                        .stream()
                        .anyMatch(list -> !list.isEmpty());

        /*
         * ==========================================
         * RAM 후보 - 메인보드별 생성
         *
         * 전체 RAM 상위 30개를 공통으로 쓰지 않고
         * 실제 추천 대상 메인보드의 memory_type에 맞춰
         * DDR4 / DDR5 후보를 각각 생성한다.
         * ==========================================
         */

        Map<Long, Mainboard> recommendationMainboards =
                new HashMap<>();

        for (List<Mainboard> cpuMainboards
                : mainboardCandidatesByCpu.values()) {

            for (Mainboard mb : cpuMainboards) {

                recommendationMainboards.put(
                        mb.getId(),
                        mb
                );
            }
        }

        Map<Long, List<Ram>> ramCandidatesByMainboard =
                new HashMap<>();

        Map<Long, Ram> ramCandidatesForScoringMap =
                new HashMap<>();

        for (Mainboard mb
                : recommendationMainboards.values()) {

            List<Ram> mbRamCandidates =
                    getRamCandidatesForMainboard(
                            mb,
                            ramPool
                    );

            ramCandidatesByMainboard.put(
                    mb.getId(),
                    mbRamCandidates
            );

            for (Ram ram : mbRamCandidates) {

                ramCandidatesForScoringMap.put(
                        ram.getId(),
                        ram
                );
            }
        }

        List<Ram> ramCandidatesForScoring =
                new ArrayList<>(
                        ramCandidatesForScoringMap.values()
                );

        boolean hasAnyRamCandidate =
                ramCandidatesByMainboard.values()
                        .stream()
                        .anyMatch(list -> !list.isEmpty());

        /*
         * ==========================================
         * Power
         *
         * 기존:
         * 와트가 높은 순서로 20개 선택
         * -> 1300W / 1600W 같은 과잉 파워가 우선됨
         *
         * 수정:
         * 1) 목표 예산 안의 파워 전체를 Pool로 구성
         * 2) 각 GPU의 recommended_power 이상만 선택
         * 3) 권장 파워 대비 출력 여유가 적당한 순서로 정렬
         * 4) 점수가 같으면 더 저렴한 제품 우선
         *
         * 즉, GPU가 750W를 요구하면
         * 1600W가 아니라 750W/800W/850W처럼
         * 필요한 용량에 가까운 파워부터 탐색한다.
         * ==========================================
         */

        List<Power> powerPool = powers.stream()
                .filter(power -> hasValidPrice(power.getPrice()))
                .filter(power -> power.getPrice() <= budget)
                .filter(power -> power.getWattage() != null)
                .filter(power -> power.getWattage() >= minimumPolicy.minWattage())
                .filter(BuildRules::standardAtxPower)
                .filter(RecommendationQualityRules::powerCandidate)
                .toList();

        powerPool = relaxBudgetLimitWhenEmpty(
                "POWER", powerPool, powers,
                power -> power.getWattage() != null
                        && power.getWattage() >= minimumPolicy.minWattage()
                        && BuildRules.standardAtxPower(power)
                        && RecommendationQualityRules.powerCandidate(power),
                Power::getPrice, 100
        );

        Map<Long, List<Power>> powerCandidatesByGpu =
                new HashMap<>();

        for (Gpu gpu : gpuCandidates) {

            powerCandidatesByGpu.put(
                    gpu.getId(),
                    getPowerCandidatesForGpu(
                            gpu,
                            powerPool
                    )
            );
        }

        List<Power> officePowerCandidates = officeWithoutDiscreteGpu
                ? getPowerCandidatesForGpu(null, powerPool)
                : List.of();

        boolean hasAnyPowerCandidate = officeWithoutDiscreteGpu
                ? !officePowerCandidates.isEmpty()
                : powerCandidatesByGpu.values().stream().anyMatch(list -> !list.isEmpty());

        /*
         * ==========================================
         * Case
         * ==========================================
         */

        List<Case> caseCandidates = cases.stream()
                .filter(pcCase -> hasValidPrice(pcCase.getPrice()))
                .filter(pcCase -> pcCase.getPrice() <= caseBudget * 1.45)
                .sorted(Comparator.comparingLong(Case::getPrice).reversed())
                .limit(20)
                .toList();

        caseCandidates = relaxBudgetLimitWhenEmpty(
                "CASE", caseCandidates, cases,
                pcCase -> true, Case::getPrice, 20
        );

        /*
         * ==========================================
         * Cooler
         *
         * 기존:
         * 가장 저렴한 쿨러부터 20개 선택
         * -> 고성능 CPU에도 1만원대 쿨러가 먼저 탐색됨
         *
         * 수정:
         * 1) 예산 범위 안의 쿨러 전체를 Pool로 구성
         * 2) CPU 소켓 호환 쿨러만 남김
         * 3) 고성능 CPU는 목표 쿨러 예산에 가장 가까운 제품 우선
         * 4) 일반 CPU는 기존처럼 저렴한 제품 우선
         *
         * 절대 가격 하한을 임의로 두지 않고
         * 이미 정의된 용도별 coolerBudget을 기준으로 균형을 맞춘다.
         * ==========================================
         */

        List<Cooler> coolerPool = coolers.stream()
                .filter(cooler -> hasValidPrice(cooler.getPrice()))
                // 고성능 CPU용 쿨러가 예산 비율 밖에 있어도 후보에서 제거하지 않는다.
                .filter(cooler -> cooler.getPrice() <= budget)
                .toList();

        coolerPool = relaxBudgetLimitWhenEmpty(
                "COOLER", coolerPool, coolers,
                cooler -> true, Cooler::getPrice, 100
        );

        Map<Long, List<Cooler>> coolerCandidatesByCpu =
                new HashMap<>();

        for (Cpu cpu : cpuCandidates) {

            coolerCandidatesByCpu.put(
                    cpu.getId(),
                    getCoolerCandidatesForCpu(
                            cpu,
                            coolerPool,
                            coolerBudget
                    )
            );
        }

        boolean hasAnyCoolerCandidate =
                coolerCandidatesByCpu.values()
                        .stream()
                        .anyMatch(list -> !list.isEmpty());

        System.out.println();
        System.out.println("======================================");
        System.out.println("[Candidate] 부품별 후보 개수");
        System.out.println("======================================");

        System.out.println("[CPU 후보] " + cpuCandidates.size());
        System.out.println("[GPU 후보] " + gpuCandidates.size());
        System.out.println("[RAM POOL] " + ramPool.size());

        for (Mainboard mb : recommendationMainboards.values().stream().limit(8).toList()) {

            List<Ram> mbRams =
                    ramCandidatesByMainboard.getOrDefault(
                            mb.getId(),
                            List.of()
                    );

            if (!mbRams.isEmpty()) {

                Ram firstRam = mbRams.get(0);

                System.out.println(
                        "[RAM 후보] MB="
                                + mb.getName()
                                + " / 메모리타입="
                                + mb.getMemoryType()
                                + " / 첫 후보="
                                + firstRam.getName()
                                + " / 가격="
                                + firstRam.getPrice()
                                + " / 후보수="
                                + mbRams.size()
                );
            }
        }
        System.out.println("[SSD 후보] " + ssdCandidates.size());
        System.out.println("[MB POOL] " + mainboardPool.size());

        for (Cpu cpu : cpuCandidates.stream().limit(8).toList()) {

            List<Mainboard> cpuMainboards =
                    mainboardCandidatesByCpu.getOrDefault(
                            cpu.getId(),
                            List.of()
                    );

            if (!cpuMainboards.isEmpty()) {

                Mainboard firstMainboard =
                        cpuMainboards.get(0);

                System.out.println(
                        "[MB 후보] CPU="
                                + cpu.getName()
                                + " / 첫 후보="
                                + firstMainboard.getName()
                                + " / 가격="
                                + firstMainboard.getPrice()
                                + " / 후보수="
                                + cpuMainboards.size()
                );
            }
        }

        System.out.println("[POWER POOL] " + powerPool.size());

        for (Gpu gpu : gpuCandidates.stream().limit(8).toList()) {

            List<Power> gpuPowers =
                    powerCandidatesByGpu.getOrDefault(
                            gpu.getId(),
                            List.of()
                    );

            if (!gpuPowers.isEmpty()) {

                Power firstPower = gpuPowers.get(0);

                System.out.println(
                        "[POWER 후보] GPU="
                                + gpu.getName()
                                + " / 권장="
                                + gpu.getRecommendedPower()
                                + "W / 우선 후보="
                                + firstPower.getWattage()
                                + "W / 후보수="
                                + gpuPowers.size()
                );
            }
        }
        System.out.println("[CASE 후보] " + caseCandidates.size());
        System.out.println("[COOLER POOL] " + coolerPool.size());

        for (Cpu cpu : cpuCandidates.stream().limit(8).toList()) {

            List<Cooler> cpuCoolers =
                    coolerCandidatesByCpu.getOrDefault(
                            cpu.getId(),
                            List.of()
                    );

            if (!cpuCoolers.isEmpty()) {

                Cooler firstCooler = cpuCoolers.get(0);

                System.out.println(
                        "[COOLER 후보] CPU="
                                + cpu.getName()
                                + " / 목표예산="
                                + coolerBudget
                                + " / 첫 후보="
                                + firstCooler.getName()
                                + " / 가격="
                                + firstCooler.getPrice()
                                + " / 후보수="
                                + cpuCoolers.size()
                );
            }
        }

        List<String> shortageReasons = new ArrayList<>();
        if (cpuCandidates.isEmpty()) shortageReasons.add("CPU 성능/가격 조건 후보 없음");
        if (!officeWithoutDiscreteGpu && gpuCandidates.isEmpty()) shortageReasons.add("GPU 성능/가격 조건 후보 없음");
        if (ramPool.isEmpty()) shortageReasons.add("RAM 최소 용량/모듈 후보 없음");
        if (!hasAnyRamCandidate || ramCandidatesForScoring.isEmpty()) shortageReasons.add("메인보드와 호환되는 RAM 후보 없음");
        if (ssdCandidates.isEmpty()) shortageReasons.add("SSD 성능/용량 후보 없음");
        if (mainboardPool.isEmpty()) shortageReasons.add("메인보드 가격 후보 없음");
        if (!hasAnyMainboardCandidate) shortageReasons.add("CPU 호환 메인보드 후보 없음");
        if (powerPool.isEmpty()) shortageReasons.add("파워 정격/규격 후보 없음");
        if (!hasAnyPowerCandidate) shortageReasons.add("GPU 요구 전력을 충족하는 파워 후보 없음");
        if (caseCandidates.isEmpty()) shortageReasons.add("케이스 가격 후보 없음");
        if (coolerPool.isEmpty()) shortageReasons.add("쿨러 가격 후보 없음");
        if (!hasAnyCoolerCandidate) shortageReasons.add("CPU 소켓 호환 쿨러 후보 없음");

        if (!shortageReasons.isEmpty()) {
            // 종전에는 여기서 빈 목록을 반환하여 최소 조건 대체 탐색에 도달하지 못했다.
            return fallbackToMinimumBuild(
                    usage, budget,
                    new MinimumBudgetCalculator.Parts(cpus, gpus, mbs, rams, ssds, powers, cases, coolers),
                    String.join(" / ", shortageReasons)
            );
        }

        /*
         * ==========================================
         * 점수 정규화 범위 생성
         * ==========================================
         */

        ScoreContext scoreContext = createScoreContext(cpus, gpus, rams, ssds);

        System.out.println();
        System.out.println("======================================");
        System.out.println("[Score] 정규화 범위");
        System.out.println("======================================");

        System.out.println(
                "[CPU BENCH] "
                        + scoreContext.cpuBench().min()
                        + " ~ "
                        + scoreContext.cpuBench().max()
        );

        System.out.println(
                "[GPU BENCH] "
                        + scoreContext.gpuBench().min()
                        + " ~ "
                        + scoreContext.gpuBench().max()
        );

        System.out.println(
                "[RAM BENCH] "
                        + scoreContext.ramBench().min()
                        + " ~ "
                        + scoreContext.ramBench().max()
        );

        System.out.println(
                "[RAM CAPACITY] "
                        + scoreContext.ramCapacity().min()
                        + " ~ "
                        + scoreContext.ramCapacity().max()
        );

        System.out.println(
                "[RAM SCORE RATIO] BENCH 50% / CAPACITY 25% / MODULE 25%"
        );

        System.out.println(
                "[RAM MODULE SCORE] 1개=40 / 2개=100 / 4개=80 / 8개=60"
        );

        System.out.println(
                "[SSD BENCH] "
                        + scoreContext.ssdBench().min()
                        + " ~ "
                        + scoreContext.ssdBench().max()
        );

        System.out.println(
                "[SSD CAPACITY] "
                        + scoreContext.ssdCapacity().min()
                        + " ~ "
                        + scoreContext.ssdCapacity().max()
        );

        /*
         * ==========================================
         * TOP 3 후보
         *
         * 동일한 핵심 조합
         * CPU + GPU + RAM + SSD
         * 은 TOP 3에 1개만 유지한다.
         *
         * 동일 핵심 조합에서 Power / Case / Cooler만
         * 다른 경우에는 점수가 같으므로 더 저렴한
         * 조합을 대표 조합으로 유지한다.
         * ==========================================
         */
        // 전략별로 독립적인 상위 후보를 유지한다.
        // 종합점수 TOP 12만 남기면 GPU/CPU 특화 후보가 탐색 중 사라질 수 있다.
        Map<RecommendationStrategy, PriorityQueue<RecommendationCandidate>> candidatePools =
                new EnumMap<>(RecommendationStrategy.class);
        for (RecommendationStrategy strategy : RecommendationStrategy.values()) {
            Comparator<RecommendationCandidate> byStrategy =
                    Comparator.<RecommendationCandidate>comparingDouble(candidate ->
                                    strategyScore(candidate.score(), candidate.cpu(), candidate.gpu(),
                                            candidate.ram(), candidate.ssd(), strategy,
                                            scoreContext, officeWithoutDiscreteGpu))
                            .thenComparing(Comparator.comparingLong(
                                    RecommendationCandidate::totalPrice).reversed());
            candidatePools.put(strategy, new PriorityQueue<>(byStrategy));
        }

        // CPU + GPU 칩셋 쌍마다 종합점수 상위 조합을 1개 보존한다.
        // 고성능 GPU + 중급 CPU와 같은 특화 후보가 TOP 12에서 사라지는 현상을 방지.
        Map<String, RecommendationCandidate> pairSeeds = new HashMap<>();
        // 같은 CPU/GPU 구성에서도 저가 RAM/SSD 후보를 보존해 재배분의 출발점으로 사용한다.
        Map<String, RecommendationCandidate> affordablePairSeeds = new HashMap<>();
        // 같은 CPU/GPU여도 메모리나 저장공간을 늘린 견적을 놓치지 않도록
        // CPU/GPU/메모리용량/SSD용량 별 대표 후보를 보존한다.
        Map<String, RecommendationCandidate> capacityVariantSeeds = new HashMap<>();
        Map<String, RecommendationCandidate> economicalVariantSeeds = new HashMap<>();

        long checkedCores = 0L;
        long compatibleCores = 0L;
        long noSupport = 0L;
        long overBudget = 0L;
        int success = 0;

        long algorithmStartNanos = System.nanoTime();
        long algorithmDeadlineNanos = algorithmStartNanos
                + RECOMMENDATION_TIMEOUT_MS * 1_000_000L;
        boolean timedOut = false;

        // GPU 벤치마크가 동일한 제조사별 RTX 5080 수십 개가
        // GPU 후보 상위권을 독점하는 현상을 막는다.
        List<Cpu> searchCpus = selectDiverseCpus(cpuCandidates, SEARCH_CPU_LIMIT);
        List<Gpu> searchGpus = officeWithoutDiscreteGpu
                ? java.util.Collections.singletonList(null)
                : selectDiverseGpus(gpuCandidates, SEARCH_GPU_LIMIT);
        List<Ssd> searchSsds = selectDiverseSsds(ssdCandidates, SEARCH_SSD_LIMIT);

        System.out.println("[Algorithm] 균형 탐색 시작 CPU=" + searchCpus.size()
                + " GPU=" + searchGpus.size() + " SSD=" + searchSsds.size());

        // CPU/GPU/보드마다 부대부품 조합을 한 번만 계산한다.
        // 비용 대비 열등한 파워/케이스/쿨러 묶음은 파레토 필터로 제거한다.
        searchLoop:
        for (Cpu cpu : searchCpus) {
            if (isTimedOut(algorithmDeadlineNanos)) { timedOut = true; break; }
            List<Mainboard> boards = mainboardCandidatesByCpu
                    .getOrDefault(cpu.getId(), List.of());
            List<Cooler> compatibleCoolers = coolerCandidatesByCpu
                    .getOrDefault(cpu.getId(), List.of());
            if (boards.isEmpty() || compatibleCoolers.isEmpty()) continue;

            for (Gpu gpu : searchGpus) {
                if (isTimedOut(algorithmDeadlineNanos)) { timedOut = true; break searchLoop; }
                long gpuPrice = gpu == null ? 0 : gpu.getPrice();
                if (cpu.getPrice() + gpuPrice >= budget) continue;
                List<Power> compatiblePowers = gpu == null
                        ? officePowerCandidates
                        : powerCandidatesByGpu.getOrDefault(gpu.getId(), List.of());
                if (compatiblePowers.isEmpty()) continue;

                for (Mainboard mb : withCheapAlternatives(
                        boards, SEARCH_BOARD_LIMIT, 2, Mainboard::getPrice)) {
                    if (!BuildRules.cpuMainboardBalanced(cpu, mb)
                            || (gpu != null && !BuildRules.gpuMainboard(gpu, mb))) {
                        continue;
                    }
                    long fixedPrice = cpu.getPrice() + gpuPrice + mb.getPrice();
                    if (fixedPrice >= budget) continue;
                    List<Ram> ramsForBoard = ramCandidatesByMainboard
                            .getOrDefault(mb.getId(), List.of());
                    if (ramsForBoard.isEmpty()) continue;

                    List<SupportOption> supportFrontier = buildSupportFrontier(
                            cpu, gpu, mb, compatibleCoolers,
                            compatiblePowers, caseCandidates, coolerBudget,
                            minimumPolicy.minWattage());
                    if (supportFrontier.isEmpty()) { noSupport++; continue; }

                    for (Ram ram : selectDiverseRams(ramsForBoard, SEARCH_RAM_LIMIT)) {
                        if (!BuildRules.ramBoard(ram, mb)) continue;
                        long corePrice = fixedPrice + ram.getPrice();
                        if (corePrice >= budget) { overBudget++; continue; }

                        for (Ssd ssd : searchSsds) {
                            checkedCores++;
                            long totalCorePrice = corePrice + ssd.getPrice();
                            long availableForSupport = budget - totalCorePrice;
                            if (availableForSupport <= 0) { overBudget++; continue; }
                            SupportOption support = findBestAffordableSupport(
                                    supportFrontier, availableForSupport);
                            if (support == null) { overBudget++; continue; }

                            long totalPrice = totalCorePrice + support.price();
                            double score = calculateTotalScore(cpu, gpu, ram, ssd,
                                    mb, support.power(), support.pcCase(),
                                    support.cooler(), usage, scoreContext, preferredBrands, coolerBudget);
                            RecommendationCandidate candidate = new RecommendationCandidate(
                                    cpu, gpu, ram, ssd, mb,
                                    support.power(), support.pcCase(), support.cooler(),
                                    totalPrice, score);
                            for (PriorityQueue<RecommendationCandidate> pool : candidatePools.values()) {
                                addTopCandidate(pool, candidate, pool.comparator());
                            }
                            String pairKey = cpu.getId() + ":" + gpuChipsetKey(gpu);
                            RecommendationCandidate prior = pairSeeds.get(pairKey);
                            if (prior == null || candidate.score() > prior.score()
                                    || (Math.abs(candidate.score() - prior.score()) < 1e-8
                                            && candidate.totalPrice() < prior.totalPrice())) {
                                pairSeeds.put(pairKey, candidate);
                            }
                            RecommendationCandidate affordable = affordablePairSeeds.get(pairKey);
                            if (affordable == null || candidate.totalPrice() < affordable.totalPrice()
                                    || (candidate.totalPrice() == affordable.totalPrice()
                                            && candidate.score() > affordable.score())) {
                                affordablePairSeeds.put(pairKey, candidate);
                            }
                            String variantKey = pairKey + ":" + getRamCapacity(ram)
                                    + ":" + getSsdCapacity(ssd) + ":" + getRamModuleCount(ram);
                            RecommendationCandidate variantPrior = capacityVariantSeeds.get(variantKey);
                            if (variantPrior == null || candidate.score() > variantPrior.score()
                                    || (Math.abs(candidate.score() - variantPrior.score()) < 1e-8
                                            && candidate.totalPrice() < variantPrior.totalPrice())) {
                                capacityVariantSeeds.put(variantKey, candidate);
                            }
                            // 같은 용량·성능군 중 저렴한 조합도 보존한다.
                            // (고득점 위주의 최고 후보만 남으면 절약형을 찾지 못함)
                            String costKey = variantKey + ":S" + (getSsdBenchmarkScore(ssd) / 10_000L);
                            RecommendationCandidate cheaper = economicalVariantSeeds.get(costKey);
                            if (cheaper == null || candidate.totalPrice() < cheaper.totalPrice()
                                    || (candidate.totalPrice() == cheaper.totalPrice()
                                            && candidate.score() > cheaper.score())) {
                                economicalVariantSeeds.put(costKey, candidate);
                            }
                            compatibleCores++;
                            success++;
                        }
                    }
                }
            }
        }
        System.out.println("[Algorithm] 핵심 조합 평가=" + checkedCores
                + ", 예산 내 완성=" + compatibleCores
                + ", 부대부품 부재=" + noSupport
                + ", 예산 미충족=" + overBudget);

        long algorithmElapsedMs =
                (System.nanoTime() - algorithmStartNanos)
                        / 1_000_000L;

        System.out.println();
        System.out.println("======================================");
        System.out.println("[Algorithm] 조합 탐색 종료");
        System.out.println("======================================");

        System.out.println("[SUCCESS] 성공 조합 수 = " + success);
        for (RecommendationStrategy strategy : RecommendationStrategy.values()) {
            System.out.println("[TOP] " + strategy + " 후보 수 = "
                    + candidatePools.get(strategy).size());
        }
        System.out.println("[TOP] 핵심 조합 중복 제거 = CPU + GPU 칩셋 + RAM/SSD 용량");
        System.out.println("[TOP] CPU/GPU 성능 쌍 = " + pairSeeds.size()
                + " / 재배분용 저가 쌍 = " + affordablePairSeeds.size());
        System.out.println("[TOP] RAM/SSD 용량 다양화 후보 = " + capacityVariantSeeds.size()
                + " / 비용 효율 후보 = " + economicalVariantSeeds.size());
        System.out.println("[TIME] 탐색 시간 = " + algorithmElapsedMs + "ms");
        System.out.println("[TIMEOUT] 제한 시간 = " + RECOMMENDATION_TIMEOUT_MS + "ms");

        if (timedOut) {
            System.out.println(
                    "[TIMEOUT] 제한 시간 초과로 탐색을 중단하고 "
                            + "현재까지의 상위 후보를 반환합니다."
            );
        }

        if (candidatePools.get(RecommendationStrategy.BALANCED).isEmpty()) {
            return fallbackToMinimumBuild(
                    usage, budget,
                    new MinimumBudgetCalculator.Parts(cpus, gpus, mbs, rams, ssds, powers, cases, coolers),
                    timedOut ? "조합 탐색 시간 제한으로 성공 후보 없음" : "조합 탐색 결과 유효한 후보 없음"
            );
        }

        /*
         * ==========================================
         * 전략별 최종 선택 + 예산 재분배
         * 1. 기존 종합 점수: 균형형
         * 2. GPU 집중 점수: GPU 중심형 (내장 GPU 사무용은 RAM/SSD 중심)
         * 3. CPU 집중 점수: CPU 중심형
         * ==========================================
         */
        MinimumBudgetCalculator.Parts allParts =
                new MinimumBudgetCalculator.Parts(cpus, gpus, mbs, rams, ssds, powers, cases, coolers);
        List<RecommendationCandidate> finalCandidates = new ArrayList<>();
        List<String> finalStrategyNames = new ArrayList<>();
        RecommendationCandidate baseline = null;

        // 세 조합을 하나씩 독립적으로 고르면, 균형형이 최고 GPU를 독점해
        // GPU 강화형이 동일 GPU + 낮은 CPU가 되는 문제가 있었다.
        // 먼저 세 전략의 선택 가능성을 함께 평가한 뒤, 각 중심형을 개선한다.
        System.out.println("[Strategy] 공동 선정: 균형형 / GPU 강화형(사무용 메모리) / CPU 강화형");
        System.out.println("[Strategy] 강제 5% 향상 규칙 없음; 고유한 핵심 조합과 최종 호환성 재검증");

        List<RecommendationCandidate> allDiverseCandidates = new ArrayList<>();
        allDiverseCandidates.addAll(pairSeeds.values());
        allDiverseCandidates.addAll(affordablePairSeeds.values());
        allDiverseCandidates.addAll(capacityVariantSeeds.values());
        allDiverseCandidates.addAll(economicalVariantSeeds.values());
        for (PriorityQueue<RecommendationCandidate> pool : candidatePools.values()) {
            allDiverseCandidates.addAll(pool);
        }
        // 후보 누락을 방지하면서 반복 평가 부담은 줄인다.
        Map<String, RecommendationCandidate> distinctBuilds = new java.util.LinkedHashMap<>();
        for (RecommendationCandidate c : allDiverseCandidates) {
            if (!validFinalRecommendation(c, budget, minimumPolicy)) continue;
            String key = c.cpu().getId() + ":" + gpuChipsetKey(c.gpu()) + ":"
                    + c.ram().getId() + ":" + c.ssd().getId();
            RecommendationCandidate old = distinctBuilds.get(key);
            if (old == null || c.score() > old.score()
                    || (Math.abs(c.score() - old.score()) < 1e-8
                    && c.totalPrice() < old.totalPrice())) distinctBuilds.put(key, c);
        }
        allDiverseCandidates = new ArrayList<>(distinctBuilds.values());
        allDiverseCandidates.sort(Comparator.comparingDouble(RecommendationCandidate::score).reversed());

        // 균형형을 전체 1위로 고정하면 상위 GPU를 독점할 수 있다.
        // 균형 점수와 나머지 두 전략의 상승 여지를 함께 고려해 기준 견적을 정한다.
        // 2점은 미세한 품질 차이를 조절할 정도의 가산점일 뿐 필수 조건이 아니다.
        RecommendationCandidate selectedBalanced = chooseJointBalancedAnchor(
                allDiverseCandidates, officeWithoutDiscreteGpu);
        if (selectedBalanced != null) {
            // 전략 공동 탐색으로 선정한 원본과 기존 균형형 TOP 후보 모두를
            // 남은 예산 재분배에 통과시켜, 균형형이 실제 최고 종합 점수를 놓치지 않도록 한다.
            List<RecommendationCandidate> balancedSeeds = new ArrayList<>();
            balancedSeeds.add(selectedBalanced);
            balancedSeeds.addAll(candidatePools.get(RecommendationStrategy.BALANCED));
            balancedSeeds.sort(Comparator.comparingDouble(RecommendationCandidate::score).reversed());
            int balancedTries = 0;
            for (RecommendationCandidate seed : balancedSeeds) {
                if (balancedTries++ >= STRATEGY_REBALANCE_ATTEMPTS) break;
                long remaining = (algorithmDeadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remaining < 150) break;
                PricePriorityOptimizer.Build upgraded = BudgetExchangeOptimizer.rebalance(
                        new PricePriorityOptimizer.Build(seed.cpu(), seed.gpu(), seed.ram(), seed.ssd(),
                                seed.mainboard(), seed.power(), seed.pcCase(), seed.cooler()),
                        budget, minimumPolicy, allParts,
                        b -> calculateTotalScore(b.cpu(), b.gpu(), b.ram(), b.ssd(),
                                b.mainboard(), b.power(), b.pcCase(), b.cooler(),
                                usage, scoreContext, preferredBrands, coolerBudget),
                        Math.min(STRATEGY_REBALANCE_MS, remaining));
                if (upgraded == null || upgraded.totalPrice() > budget ||
                        !PricePriorityOptimizer.isCompatible(upgraded, minimumPolicy)) continue;
                double score = calculateTotalScore(upgraded.cpu(), upgraded.gpu(),
                        upgraded.ram(), upgraded.ssd(), upgraded.mainboard(),
                        upgraded.power(), upgraded.pcCase(), upgraded.cooler(),
                        usage, scoreContext, preferredBrands, coolerBudget);
                RecommendationCandidate candidate = new RecommendationCandidate(
                        upgraded.cpu(), upgraded.gpu(), upgraded.ram(), upgraded.ssd(),
                        upgraded.mainboard(), upgraded.power(), upgraded.pcCase(),
                        upgraded.cooler(), upgraded.totalPrice(), score);
                if (validFinalRecommendation(candidate, budget, minimumPolicy)
                        && (candidate.score() > selectedBalanced.score() + 1e-8
                        || (Math.abs(candidate.score() - selectedBalanced.score()) < 1e-8
                        && candidate.totalPrice() < selectedBalanced.totalPrice()))) {
                    selectedBalanced = candidate;
                }
            }
            selectedBalanced = rebalanceJointAnchorForGpuUpgrade(
                    selectedBalanced, allDiverseCandidates, officeWithoutDiscreteGpu);
            finalCandidates.add(selectedBalanced);
            finalStrategyNames.add("균형형");
            baseline = selectedBalanced;
            System.out.println("[Strategy] BALANCED : " + selectedBalanced.cpu().getName()
                    + " / GPU=" + (selectedBalanced.gpu() == null ? "내장" : selectedBalanced.gpu().getName())
                    + " / 종합=" + String.format(java.util.Locale.ROOT, "%.2f", selectedBalanced.score()));
        }

        for (RecommendationStrategy strategy : List.of(
                RecommendationStrategy.GPU_FOCUSED, RecommendationStrategy.CPU_FOCUSED)) {
            RecommendationCandidate reference = baseline;
            List<RecommendationCandidate> ranked = new ArrayList<>(allDiverseCandidates);
            // 성능 특화형은 더 높은 성능을 우선하되 RAM/SSD, 전체 점수도 함께 본다.
            ranked.sort(Comparator.comparingDouble((RecommendationCandidate c) ->
                    effectiveFocusValue(strategy, c, reference, scoreContext,
                            officeWithoutDiscreteGpu)).reversed()
                    .thenComparingLong(RecommendationCandidate::totalPrice));

            RecommendationCandidate selected = null;
            for (RecommendationCandidate c : ranked) {
                if (isDistinctFromSelected(finalCandidates, c)) {
                    selected = c;
                    break;
                }
            }
            // 최상위 후보와 저가 후보를 재분배해 CPU/GPU의 숨은 개선 가능성 탐색.
            int rebalanced = 0;
            int attempts = 0;
            for (RecommendationCandidate seed : ranked) {
                if (attempts >= STRATEGY_REBALANCE_ATTEMPTS) break;
                if (!isDistinctFromSelected(finalCandidates, seed)) continue;
                long remainingMs = (algorithmDeadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs < 150L) break;
                attempts++;
                PricePriorityOptimizer.Build build = BudgetExchangeOptimizer.rebalance(
                        new PricePriorityOptimizer.Build(seed.cpu(), seed.gpu(), seed.ram(),
                                seed.ssd(), seed.mainboard(), seed.power(), seed.pcCase(), seed.cooler()),
                        budget, minimumPolicy, allParts,
                        candidate -> {
                            double score = calculateTotalScore(candidate.cpu(), candidate.gpu(),
                                    candidate.ram(), candidate.ssd(), candidate.mainboard(),
                                    candidate.power(), candidate.pcCase(), candidate.cooler(),
                                    usage, scoreContext, preferredBrands, coolerBudget);
                            RecommendationCandidate temp = new RecommendationCandidate(
                                    candidate.cpu(), candidate.gpu(), candidate.ram(), candidate.ssd(),
                                    candidate.mainboard(), candidate.power(), candidate.pcCase(),
                                    candidate.cooler(), candidate.totalPrice(), score);
                            return effectiveFocusValue(strategy, temp, reference, scoreContext,
                                    officeWithoutDiscreteGpu);
                        }, Math.min(STRATEGY_REBALANCE_MS, remainingMs));
                if (build == null || build.totalPrice() > budget ||
                        !PricePriorityOptimizer.isCompatible(build, minimumPolicy)) continue;
                double score = calculateTotalScore(build.cpu(), build.gpu(), build.ram(),
                        build.ssd(), build.mainboard(), build.power(), build.pcCase(),
                        build.cooler(), usage, scoreContext, preferredBrands, coolerBudget);
                RecommendationCandidate upgraded = new RecommendationCandidate(
                        build.cpu(), build.gpu(), build.ram(), build.ssd(), build.mainboard(),
                        build.power(), build.pcCase(), build.cooler(), build.totalPrice(), score);
                if (!validFinalRecommendation(upgraded, budget, minimumPolicy)
                        || !isDistinctFromSelected(finalCandidates, upgraded)) continue;
                rebalanced++;
                if (selected == null || effectiveFocusValue(strategy, upgraded, reference,
                        scoreContext, officeWithoutDiscreteGpu) > effectiveFocusValue(
                        strategy, selected, reference, scoreContext, officeWithoutDiscreteGpu) + 1e-8) {
                    selected = upgraded;
                }
            }
            if (selected != null) {
                finalCandidates.add(selected);
                String name = focusName(strategy, selected, reference, officeWithoutDiscreteGpu);
                finalStrategyNames.add(name);
                System.out.println("[Strategy] " + strategy + " : " + selected.cpu().getName()
                        + " (" + getCpuBenchmarkScore(selected.cpu()) + ")"
                        + " / GPU=" + (selected.gpu() == null ? "내장"
                        : selected.gpu().getName() + " (" + getGpuBenchmarkScore(selected.gpu()) + ")")
                        + " / 종합=" + String.format(java.util.Locale.ROOT, "%.2f", selected.score())
                        + " / 재배분=" + rebalanced + " / 명칭=" + name);
            }
        }

        // 모든 부품은 유효하더라도 CPU·GPU가 동일한 견적은 3개로 세지 않는다.
        // 향상 가능한 조합을 찾지 못한 경우에만 다른 핵심 구성을 채운다.
        if (finalCandidates.size() < MAX_RECOMMENDATION_COUNT) {
            for (RecommendationCandidate c : allDiverseCandidates) {
                if (finalCandidates.size() >= MAX_RECOMMENDATION_COUNT) break;
                if (!isDistinctFromSelected(finalCandidates, c)) continue;
                finalCandidates.add(c);
                finalStrategyNames.add("다른 핵심 구성");
            }
        }

        System.out.println("[Strategy] 최종 추천 개수 = " + finalCandidates.size());

        /*
         * ==========================================
         * Response
         * ==========================================
         */

        List<ResponseDTO.Recommendation> results = new ArrayList<>();
        for (int i = 0; i < finalCandidates.size(); i++) {
            RecommendationCandidate candidate = finalCandidates.get(i);
            String strategyName = finalStrategyNames.get(i);
            results.add(new ResponseDTO.Recommendation(
                    "추천 조합 " + (i + 1) + " (" + strategyName + ")",
                    candidate.totalPrice(), createRecommendedPartDetails(candidate)));
        }

        /*
         * ==========================================
         * 결과 로그
         * ==========================================
         */

        System.out.println();
        System.out.println("======================================");
        System.out.println("[Response] 최종 응답 생성");
        System.out.println("======================================");

        System.out.println(
                "[Response] 반환 추천 개수 = "
                        + results.size()
        );

        for (int i = 0; i < finalCandidates.size(); i++) {

            RecommendationCandidate c =
                    finalCandidates.get(i);

            double cpuScore =
                    getNormalizedCpuScore(
                            c.cpu(),
                            scoreContext
                    );

            double gpuScore =
                    getNormalizedGpuScore(
                            c.gpu(),
                            scoreContext
                    );

            double ramScore = getBoardAdjustedRamScore(
                    c.ram(), c.mainboard(),
                    getNormalizedRamScore(c.ram(), scoreContext)
            );

            double ramBenchScore =
                    normalizeScore(
                            getRamBenchmarkScore(c.ram()),
                            scoreContext.ramBench()
                    );

            double ramCapacityScore =
                    normalizeLogCapacity(
                            getRamCapacity(c.ram()),
                            scoreContext.ramCapacity()
                    );

            double ramModuleScore =
                    getRamModuleConfigurationScore(
                            c.ram()
                    );

            double ssdScore =
                    getNormalizedSsdScore(
                            c.ssd(),
                            scoreContext
                    );

            System.out.println();
            System.out.println(
                    "---------- 추천 "
                            + (i + 1)
                            + " ----------"
            );

            System.out.println(
                    "총액 = "
                            + c.totalPrice()
            );

            System.out.printf(
                    "최종 점수 = %.2f%n",
                    c.score()
            );
            System.out.println("남은 예산 = " + (budget - c.totalPrice()));
            System.out.println("메인보드 사용/목표 = " + c.mainboard().getPrice() + "/" + mbBudget);
            System.out.println("파워 사용/목표 = " + c.power().getPrice() + "/" + powerBudget);

            double brandPreferenceBonus =
                    calculateBrandPreferenceBonus(
                            c.cpu(),
                            c.gpu(),
                            c.mainboard(),
                            preferredBrands
                    );

            System.out.printf(
                    "브랜드 선호 가산점 = %.2f%n",
                    brandPreferenceBonus
            );

            System.out.println(
                    "CPU = "
                            + c.cpu().getName()
            );

            System.out.println(
                    "CPU Bench = "
                            + getCpuBenchmarkScore(
                            c.cpu()
                    )
            );

            System.out.printf(
                    "CPU Score = %.2f%n",
                    cpuScore
            );

            System.out.println("GPU = " + (c.gpu() == null ? "내장 그래픽" : c.gpu().getName()));

            System.out.println(
                    "GPU Bench = "
                            + getGpuBenchmarkScore(
                            c.gpu()
                    )
            );

            System.out.printf(
                    "GPU Score = %.2f%n",
                    gpuScore
            );

            System.out.println(
                    "RAM = "
                            + c.ram().getName()
            );

            System.out.println(
                    "RAM Capacity = "
                            + c.ram().getCapacity()
            );

            System.out.println(
                    "RAM Module Count = "
                            + c.ram().getModuleCount()
            );

            System.out.println(
                    "RAM Bench = "
                            + getRamBenchmarkScore(
                            c.ram()
                    )
            );

            System.out.printf(
                    "RAM Bench Score = %.2f%n",
                    ramBenchScore
            );

            System.out.printf(
                    "RAM Capacity Score = %.2f%n",
                    ramCapacityScore
            );

            System.out.printf(
                    "RAM Module Score = %.2f%n",
                    ramModuleScore
            );

            System.out.printf(
                    "RAM Final Score = %.2f%n",
                    ramScore
            );

            System.out.println(
                    "SSD = "
                            + c.ssd().getName()
            );

            System.out.println(
                    "SSD Capacity = "
                            + c.ssd().getCapacity()
            );

            System.out.println(
                    "SSD Bench = "
                            + getSsdBenchmarkScore(
                            c.ssd()
                    )
            );

            System.out.printf(
                    "SSD Score = %.2f%n",
                    ssdScore
            );

            System.out.println(
                    "MB = "
                            + c.mainboard().getName()
            );

            System.out.println(
                    "POWER = "
                            + c.power().getName()
            );

            System.out.println("GPU Recommended Power = "
                    + (c.gpu() == null ? "외장 GPU 없음" : c.gpu().getRecommendedPower() + "W"));

            System.out.println(
                    "POWER Wattage = "
                            + c.power().getWattage()
                            + "W"
            );

            if (c.gpu() != null
                    && c.gpu().getRecommendedPower() != null
                    && c.power().getWattage() != null) {

                System.out.println(
                        "POWER Headroom = "
                                + (c.power().getWattage()
                                - c.gpu().getRecommendedPower())
                                + "W"
                );
            }

            System.out.println(
                    "CASE = "
                            + c.pcCase().getName()
            );

            System.out.println(
                    "COOLER = "
                            + c.cooler().getName()
            );
        }

        System.out.println("======================================");
        System.out.println();

        return new ResponseDTO.ResultList(results);
    }

    /**
     * 부품별 목표 예산은 추천 탐색용 휴리스틱이다. 최소 성능/규격 조건을
     * 충족하는 제품이 모두 목표 가격 밖에 있을 때만 가격 상한을 해제한다.
     * 이 경우 가격이 낮은 순서로 먼저 탐색하여 조합을 발견할 가능성을 높인다.
     * 최종 합계는 아래 탐색 단계에서 반드시 사용자 총예산 이내로 검사한다.
     */
    private <T> List<T> relaxBudgetLimitWhenEmpty(
            String category,
            List<T> selected,
            List<T> source,
            Predicate<T> requirements,
            Function<T, Long> priceExtractor,
            int limit
    ) {
        if (!selected.isEmpty()) {
            return selected;
        }

        List<T> relaxed = source.stream()
                .filter(requirements)
                .filter(item -> hasValidPrice(priceExtractor.apply(item)))
                .sorted(Comparator.comparingLong(item -> priceExtractor.apply(item)))
                .limit(limit)
                .toList();

        if (!relaxed.isEmpty()) {
            System.out.println("[Candidate Relax] " + category
                    + " : 기존 예산 상한에 해당하는 상품이 없어 가격 상한을 해제했습니다."
                    + " (후보 " + relaxed.size() + "개, 최저가 "
                    + priceExtractor.apply(relaxed.get(0)) + "원)");
        }
        return relaxed;
    }

    /**
     * 예산 비율에 따른 후보 추출·조합 탐색이 실패했을 때 전체 DB를 대상으로
     * 최소 성능과 물리적 호환성을 만족하는 '최저 가격 조합'을 한 번 더 탐색한다.
     * 반환 전에는 사용자 총 예산을 재검증한다.
     */
    private ResponseDTO.ResultList fallbackToMinimumBuild(
            String usage,
            long budget,
            MinimumBudgetCalculator.Parts allParts,
            String reason
    ) {
        System.out.println("[Fallback] 기본 추천 실패 이유: " + reason);
        System.out.println("[Fallback] 전체 DB 기반 최소 조건 조합을 탐색합니다.");

        var minimum = MinimumBudgetCalculator.find(usage, allParts);
        if (minimum.isEmpty()) {
            System.out.println("[Fallback] 최소 성능 및 호환성을 만족하는 구성 자체가 없습니다.");
            return new ResponseDTO.ResultList(List.of());
        }

        var min = minimum.get();
        if (min.amount() > budget) {
            System.out.println("[Fallback] 최소 조건 조합 " + min.amount()
                    + "원이 요청 예산 " + budget + "원을 초과합니다."
                    + " 저장된 최소 예산이 오래되었는지도 확인하세요.");
            return new ResponseDTO.ResultList(List.of());
        }

        Cpu cpu = allParts.cpus().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.cpuId()))
                .findFirst().orElse(null);
        Gpu gpu = min.gpuId() == null ? null : allParts.gpus().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.gpuId()))
                .findFirst().orElse(null);
        Ram ram = allParts.rams().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.ramId()))
                .findFirst().orElse(null);
        Ssd ssd = allParts.ssds().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.ssdId()))
                .findFirst().orElse(null);
        Mainboard mainboard = allParts.mainboards().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.mainboardId()))
                .findFirst().orElse(null);
        Power power = allParts.powers().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.powerId()))
                .findFirst().orElse(null);
        Case pcCase = allParts.cases().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.caseId()))
                .findFirst().orElse(null);
        Cooler cooler = allParts.coolers().stream()
                .filter(x -> x.getId() != null && x.getId().equals(min.coolerId()))
                .findFirst().orElse(null);

        if (cpu == null || ram == null || ssd == null || mainboard == null
                || power == null || pcCase == null || cooler == null
                || (MinimumBudgetCalculator.Policy.of(usage).needsGpu() && gpu == null)) {
            System.out.println("[Fallback] 계산된 조합의 부품을 DB 조회 결과에서 찾을 수 없습니다.");
            return new ResponseDTO.ResultList(List.of());
        }
        // 최소가 대체 추천에도 고성능 CPU + 보급형 메인보드 조합은 반환하지 않는다.
        if (!BuildRules.cpuMainboardBalanced(cpu, mainboard)) {
            System.out.println("[Fallback] CPU/메인보드 균형 조건 미충족으로 대체 조합 제외");
            return new ResponseDTO.ResultList(List.of());
        }

        MinimumBudgetCalculator.Policy effectivePolicy = MinimumBudgetCalculator.effectivePolicy(
                usage, allParts.cpus(), allParts.gpus());
        PricePriorityOptimizer.Build safeSeed = new PricePriorityOptimizer.Build(
                cpu, gpu, ram, ssd, mainboard, power, pcCase, cooler);
        if (!PricePriorityOptimizer.isCompatible(safeSeed, effectivePolicy)) {
            // 기존 최소 계산식은 변경하지 않는다. 대체 견적에서만 기존 DB 부품으로 보강한다.
            safeSeed = repairFallbackSupport(safeSeed, budget, effectivePolicy, allParts);
            if (safeSeed == null) {
                System.out.println("[Fallback] 보조 부품 품질 하한을 만족하는 예산 내 구성 없음");
                return new ResponseDTO.ResultList(List.of());
            }
        }

        ScoreContext fallbackScoreContext = createScoreContext(
                allParts.cpus(), allParts.gpus(), allParts.rams(), allParts.ssds());
        PricePriorityOptimizer.Build optimized = BudgetExchangeOptimizer.rebalance(
                safeSeed,
                budget, effectivePolicy,
                allParts,
                build -> calculateTotalScore(build.cpu(), build.gpu(), build.ram(),
                        build.ssd(), build.mainboard(), build.power(), build.pcCase(),
                        build.cooler(), usage, fallbackScoreContext, Set.of(),
                        Math.max(1L, budget / 50)),
                400L);
        RecommendationCandidate candidate = new RecommendationCandidate(
                optimized.cpu(), optimized.gpu(), optimized.ram(), optimized.ssd(),
                optimized.mainboard(), optimized.power(), optimized.pcCase(), optimized.cooler(),
                optimized.totalPrice(), 0.0
        );
        System.out.println("[Fallback] 최소 조합 + 잔여 예산 업그레이드: " + optimized.totalPrice() + "원");
        return new ResponseDTO.ResultList(List.of(
                new ResponseDTO.Recommendation(
                        "추천 조합 1",
                        optimized.totalPrice(),
                        createRecommendedPartDetails(candidate)
                )
        ));
    }

    /** 기존 최소 견적 계산 결과를 재사용하되 보조 부품만 품질 기준에 맞게 보강한다. */
    private PricePriorityOptimizer.Build repairFallbackSupport(
            PricePriorityOptimizer.Build seed, long budget,
            MinimumBudgetCalculator.Policy policy, MinimumBudgetCalculator.Parts allParts) {
        List<Power> suitablePowers = allParts.powers().stream()
                .filter(power -> RecommendationQualityRules.powerSuitable(
                        seed.cpu(), seed.gpu(), power, policy.minWattage()))
                .sorted(Comparator.comparingLong(Power::getPrice)).toList();
        List<Cooler> suitableCoolers = allParts.coolers().stream()
                .filter(cooler -> RecommendationQualityRules.coolerSuitable(seed.cpu(), cooler))
                .sorted(Comparator.comparingLong(Cooler::getPrice)).toList();
        List<Case> fittingCases = allParts.cases().stream()
                .filter(pcCase -> hasValidPrice(pcCase.getPrice())
                        && BuildRules.boardCase(seed.mainboard(), pcCase)
                        && BuildRules.gpuCase(seed.gpu(), pcCase))
                .sorted(Comparator.comparingLong(Case::getPrice)).toList();
        PricePriorityOptimizer.Build best = null;
        for (Power power : suitablePowers) {
            for (Cooler cooler : suitableCoolers) {
                for (Case pcCase : fittingCases) {
                    if (!BuildRules.coolerCase(cooler, pcCase)) continue;
                    PricePriorityOptimizer.Build alternative = new PricePriorityOptimizer.Build(
                            seed.cpu(), seed.gpu(), seed.ram(), seed.ssd(),
                            seed.mainboard(), power, pcCase, cooler);
                    if (alternative.totalPrice() > budget) break; // 케이스 가격 오름차순
                    if (PricePriorityOptimizer.isCompatible(alternative, policy)
                            && (best == null || alternative.totalPrice() < best.totalPrice())) {
                        best = alternative;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /*
     * ==========================================
     * 브랜드 선호도 처리
     * ==========================================
     */

    private Set<String> normalizePreferredBrands(
            List<String> brands
    ) {

        if (brands == null
                || brands.isEmpty()) {

            return Set.of();
        }

        Set<String> result =
                new LinkedHashSet<>();

        for (String brand : brands) {

            String normalizedBrand =
                    normalizeBrandValue(
                            brand
                    );

            if (!normalizedBrand.isBlank()) {

                result.add(
                        normalizedBrand
                );
            }
        }

        return Set.copyOf(
                result
        );
    }

    private String normalizeBrandValue(
            String value
    ) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toUpperCase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
    }

    private boolean isPreferredBrand(
            String value,
            Set<String> preferredBrands
    ) {

        if (preferredBrands == null
                || preferredBrands.isEmpty()
                || value == null) {

            return false;
        }

        String normalizedValue =
                normalizeBrandValue(
                        value
                );

        return !normalizedValue.isBlank()
                && preferredBrands.contains(
                normalizedValue
        );
    }

    private boolean isCpuPreferred(
            Cpu cpu,
            Set<String> preferredBrands
    ) {

        return cpu != null
                && isPreferredBrand(
                cpu.getBrand(),
                preferredBrands
        );
    }

    private boolean isGpuPreferred(
            Gpu gpu,
            Set<String> preferredBrands
    ) {

        if (gpu == null) {
            return false;
        }

        /*
         * GPU는 실제 제조사(ASUS/MSI/GIGABYTE 등)와
         * GPU 칩셋 제조사(NVIDIA/AMD/INTEL)를 모두 확인한다.
         *
         * 두 값이 동시에 선호 목록에 있어도 GPU는 한 번만 가산한다.
         */
        return isPreferredBrand(
                gpu.getBrand(),
                preferredBrands
        )
                || isPreferredBrand(
                gpu.getChipsetBrand(),
                preferredBrands
        );
    }

    private boolean isMainboardPreferred(
            Mainboard mainboard,
            Set<String> preferredBrands
    ) {

        return mainboard != null
                && isPreferredBrand(
                mainboard.getBrand(),
                preferredBrands
        );
    }

    private double calculateBrandPreferenceBonus(
            Cpu cpu,
            Gpu gpu,
            Mainboard mainboard,
            Set<String> preferredBrands
    ) {

        if (preferredBrands == null
                || preferredBrands.isEmpty()) {

            return 0.0;
        }

        double bonus = 0.0;

        if (isCpuPreferred(
                cpu,
                preferredBrands
        )) {

            bonus +=
                    BRAND_MATCH_SCORE_BONUS;
        }

        if (isGpuPreferred(
                gpu,
                preferredBrands
        )) {

            bonus +=
                    BRAND_MATCH_SCORE_BONUS;
        }

        if (isMainboardPreferred(
                mainboard,
                preferredBrands
        )) {

            bonus +=
                    BRAND_MATCH_SCORE_BONUS;
        }

        return bonus;
    }

    /*
     * CPU / GPU 후보 30개를 자르기 전에
     * 선호 브랜드 제품에만 약 5%의 정렬 우대를 준다.
     * 성능 점수 자체를 대체하거나 강제 필터링하지 않는다.
     */

    private double getCpuCandidateRankingScore(Cpu cpu, Set<String> preferredBrands) {
        // 1순위 벤치마크, 선호 브랜드는 동일 벤치마크에서만 사용한다.
        return getCpuBenchmarkScore(cpu);
    }

    private double getGpuCandidateRankingScore(Gpu gpu, Set<String> preferredBrands) {
        return getGpuBenchmarkScore(gpu);
    }

    /*
     * 메인보드 후보는 지원 사양을 먼저 비교하고
     * 동점일 때만 선호 브랜드 및 가격으로 정렬한다.
     *
     * 실제 제품 가격이나 총 견적 금액은 절대 변경하지 않는다.
     */

    /**
     * DB에 전원부 페이즈/VRM 냉각, SSD M.2 세대, 파워 효율,
     * 케이스 팬/통풍 자료가 없으므로 해당 성능을 추정하지 않는다.
     * 메인보드의 현재 저장된 칩셋/PCIe/메모리 규격만 평가한다.
     * 가격은 동점 처리에만 사용한다.
     */
    /** 높은 점수 후보를 우선하되, 예산이 부족할 때 선택할 저가 대안도 남긴다. */
    private <T> List<T> withCheapAlternatives(
            List<T> sortedBestFirst, int limit, int cheapSlots,
            Function<T, Long> priceGetter) {
        if (sortedBestFirst == null || sortedBestFirst.isEmpty()) return List.of();
        List<T> chosen = new ArrayList<>();
        int bestCount = Math.max(0, limit - cheapSlots);
        for (T item : sortedBestFirst) {
            if (chosen.size() >= bestCount) break;
            if (!chosen.contains(item)) chosen.add(item);
        }
        List<T> cheapFirst = sortedBestFirst.stream()
                .sorted(Comparator.comparingLong(item -> priceGetter.apply(item)))
                .toList();
        for (T item : cheapFirst) {
            if (chosen.size() >= limit) break;
            if (!chosen.contains(item)) chosen.add(item);
        }
        return chosen;
    }

    /**
     * CPU/GPU/메인보드별로 쿨러/파워/케이스의 가격-구성 점수 파레토 집합을 계산한다.
     * 최종 가격이 아닌 각 부품의 가격 합계만 사용하므로 같은 핵심 부품 조합에 재사용 가능하다.
     */
    private List<SupportOption> buildSupportFrontier(
            Cpu cpu, Gpu gpu, Mainboard board,
            List<Cooler> coolers, List<Power> powers, List<Case> cases,
            long targetCoolerBudget, long minWattage) {
        List<SupportOption> options = new ArrayList<>();
        List<Cooler> searchCoolers = withCheapAlternatives(
                coolers, SUPPORT_COOLER_LIMIT, 2, Cooler::getPrice);
        List<Case> searchCases = withCheapAlternatives(
                cases, SUPPORT_CASE_LIMIT, 3, Case::getPrice);
        List<Power> searchPowers = withCheapAlternatives(
                powers, SUPPORT_POWER_LIMIT, 4, Power::getPrice);
        for (Cooler cooler : searchCoolers) {
            if (!RecommendationQualityRules.coolerSuitable(cpu, cooler)) continue;
            for (Case pcCase : searchCases) {
                if (!BuildRules.boardCase(board, pcCase)
                        || !BuildRules.gpuCase(gpu, pcCase)
                        || !BuildRules.coolerCase(cooler, pcCase)) continue;
                for (Power power : searchPowers) {
                    if (!BuildRules.standardAtxPower(power)
                            || !RecommendationQualityRules.powerSuitable(cpu, gpu, power, minWattage)) continue;
                    long price = cooler.getPrice() + pcCase.getPrice() + power.getPrice();
                    double quality = getPowerQualityScore(gpu, power) * 0.07
                            + getCaseFitScore(gpu, cooler, pcCase) * 0.04
                            + getCoolerBalanceScore(cpu, cooler, targetCoolerBudget) * 0.01;
                    options.add(new SupportOption(cooler, power, pcCase, price, quality));
                }
            }
        }
        options.sort(Comparator.comparingLong(SupportOption::price)
                .thenComparing(Comparator.comparingDouble(SupportOption::quality).reversed()));

        // 저렴하면서 품질점수도 낮지 않은 대안을 보존하고 열등한 옵션만 제거한다.
        List<SupportOption> frontier = new ArrayList<>();
        double bestQuality = -1.0;
        for (SupportOption option : options) {
            if (option.quality() > bestQuality + 1e-7) {
                frontier.add(option);
                bestQuality = option.quality();
            }
        }
        return frontier;
    }

    private SupportOption findBestAffordableSupport(
            List<SupportOption> frontier, long availableBudget) {
        SupportOption best = null;
        // 파레토 집합은 가격/품질이 증가하는 순서이므로 마지막 허용 항목이 최선이다.
        for (SupportOption item : frontier) {
            if (item.price() > availableBudget) break;
            best = item;
        }
        return best;
    }

    private double getPowerQualityScore(Gpu gpu, Power power) {
        double headroom = getPowerHeadroomScore(gpu, power);
        String name = normalize(power == null ? null : power.getName());
        double tier = 55.0; // 제품명에서 효율 등급을 확인할 수 없으면 중립 평가
        if (hasAnyToken(name, "티타늄", "TITANIUM")) tier = 100.0;
        else if (hasAnyToken(name, "플래티넘", "PLATINUM")) tier = 97.0;
        else if (hasAnyToken(name, "골드", "GOLD")) tier = 88.0;
        else if (hasAnyToken(name, "실버", "SILVER")) tier = 68.0;
        else if (hasAnyToken(name, "브론즈", "BRONZE")) tier = 42.0;
        // 80PLUS는 효율 인증이지 실제 보호회로/품질 보증은 아님.
        return Math.min(100.0, headroom * 0.50 + tier * 0.50);
    }

    private double getCoolerBalanceScore(Cpu cpu, Cooler cooler, long budget) {
        if (cooler == null || !hasValidPrice(cooler.getPrice())) return 0.0;
        if (!isHighPerformanceCpu(cpu)) return 80.0;
        if (budget <= 0) return 70.0;
        // 성능/냉각능력 정보가 없으므로 가격이 곧 냉각력이라는 뜻은 아니다.
        double relative = (double) cooler.getPrice() / budget;
        return Math.max(20.0, 100.0 - Math.abs(relative - 0.85) * 50.0);
    }

    // 탐색 폭을 확보하기 위한 후보별 다양성 제한. 정렬된 기존 후보 집합을 입력받는다.
    private List<Cpu> selectDiverseCpus(List<Cpu> candidates, int limit) {
        // 상위 점수만 사용하면 특화형의 가성비 중급 CPU가 빠질 수 있다.
        Map<Long, Cpu> chosen = new java.util.LinkedHashMap<>();
        Set<Long> benchSeen = new java.util.HashSet<>();
        int top = Math.max(1, limit / 3);
        for (Cpu cpu : candidates) {
            if (!benchSeen.add(getCpuBenchmarkScore(cpu))) continue;
            chosen.putIfAbsent(cpu.getId(), cpu);
            if (chosen.size() >= top) break;
        }
        // 최고 성능군 바로 아래의 CPU를 별도로 확보한다.
        // 전체 DB 기준 분위수만 사용하면 55~60K 점수대의 CPU가 빠질 수 있다.
        List<Cpu> byBenchmark = new ArrayList<>();
        Set<Long> middleBenchmarks = new java.util.HashSet<>();
        for (Cpu cpu : candidates) {
            if (middleBenchmarks.add(getCpuBenchmarkScore(cpu))) byBenchmark.add(cpu);
        }
        int middleSlots = Math.max(1, limit / 3);
        for (int i = 0; i < middleSlots; i++) {
            int index = Math.min(byBenchmark.size() - 1, top + i * 2);
            if (index >= 0) chosen.putIfAbsent(byBenchmark.get(index).getId(), byBenchmark.get(index));
        }
        // 성능 대비 저렴한 모델도 충분히 확보한다.
        for (Cpu cpu : candidates.stream().sorted(Comparator.comparingLong(Cpu::getPrice)).toList()) {
            chosen.putIfAbsent(cpu.getId(), cpu);
            if (chosen.size() >= limit) break;
        }
        return new ArrayList<>(chosen.values());
    }

    private List<Gpu> selectDiverseGpus(List<Gpu> candidates, int limit) {
        // 벤치마크가 같은 GPU는 제조사가 달라도 제일 저렴한 상품을 대표로 사용한다.
        // 다양한 GPU 칩셋을 수집해서 최고 성능 제품과 가성비 대안이 함께 탐색되게 한다.
        Map<String, Gpu> cheapestByChipset = new java.util.LinkedHashMap<>();
        for (Gpu gpu : candidates) {
            String chipset = gpuChipsetKey(gpu);
            Gpu old = cheapestByChipset.get(chipset);
            if (old == null || gpu.getPrice() < old.getPrice()) {
                cheapestByChipset.put(chipset, gpu);
            }
        }
        List<Gpu> ordered = cheapestByChipset.values().stream()
                .sorted(Comparator.comparingLong((Gpu gpu) -> getGpuBenchmarkScore(gpu)).reversed()
                        .thenComparingLong(Gpu::getPrice))
                .toList();
        // GPU 위주 조합뿐 아니라 CPU 업그레이드 비용을 마련할 저가 GPU도 보존한다.
        Map<Long, Gpu> chosen = new java.util.LinkedHashMap<>();
        for (Gpu gpu : ordered) {
            chosen.putIfAbsent(gpu.getId(), gpu);
            if (chosen.size() >= Math.max(1, limit - 4)) break;
        }
        for (Gpu gpu : ordered.stream().sorted(Comparator.comparingLong(Gpu::getPrice)).toList()) {
            chosen.putIfAbsent(gpu.getId(), gpu);
            if (chosen.size() >= limit) break;
        }
        return new ArrayList<>(chosen.values());
    }

    /** 높은 성능과 적정 가격을 함께 탐색한다. 성능 상위 후보만 남기지 않는다. */
    private List<Ram> selectDiverseRams(List<Ram> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) return List.of();
        Map<Long, Ram> chosen = new java.util.LinkedHashMap<>();
        // 성능 우수 모델과 저비용 모델을 각각 확보한다.
        addRamsWithDiversity(chosen, candidates, Math.max(1, limit / 4), limit, 1);
        List<Ram> cheap = candidates.stream()
                .sorted(Comparator.comparingLong(Ram::getPrice)
                        .thenComparing(Comparator.comparingDouble((Ram r) -> getRamBenchmarkScore(r)).reversed()))
                .toList();
        addRamsWithDiversity(chosen, cheap, limit, limit, 2);
        addRamsWithDiversity(chosen, candidates, limit, limit, 2);
        return new ArrayList<>(chosen.values());
    }

    private void addRamsWithDiversity(Map<Long, Ram> chosen, List<Ram> ordered,
                                      int target, int limit, int maxPerCapacity) {
        Map<String, Integer> counts = new HashMap<>();
        for (Ram ram : chosen.values()) {
            String key = getRamCapacity(ram) + "/" + getRamModuleCount(ram);
            counts.merge(key, 1, Integer::sum);
        }
        for (Ram ram : ordered) {
            if (chosen.size() >= target || chosen.size() >= limit) break;
            String key = getRamCapacity(ram) + "/" + getRamModuleCount(ram);
            if (counts.getOrDefault(key, 0) >= maxPerCapacity) continue;
            if (chosen.putIfAbsent(ram.getId(), ram) == null) {
                counts.merge(key, 1, Integer::sum);
            }
        }
    }

    /** SSD 고성능만 탐색하던 문제를 개선: 상위 성능, 용량, 가성비 대표를 고르게 남긴다. */
    private List<Ssd> selectDiverseSsds(List<Ssd> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) return List.of();
        Map<Long, Ssd> chosen = new java.util.LinkedHashMap<>();
        long maxBench = candidates.stream().mapToLong(this::getSsdBenchmarkScore).max().orElse(0L);
        int performanceSlots = Math.max(1, limit / 3);
        for (Ssd ssd : candidates) {
            chosen.putIfAbsent(ssd.getId(), ssd);
            if (chosen.size() >= performanceSlots) break;
        }
        // 최소 55% 벤치마크를 가진 가격 경쟁력 있는 SSD도 탐색에 포함한다.
        // 별도 SSD 성능 하한은 사용하지 않고 최종 점수로 비교한다.
        List<Ssd> affordable = candidates.stream()
                .filter(ssd -> maxBench <= 0 || getSsdBenchmarkScore(ssd) >= maxBench * 0.55)
                .sorted(Comparator.comparingLong(Ssd::getPrice)
                        .thenComparing(Comparator.comparingLong((Ssd ssd) -> getSsdBenchmarkScore(ssd)).reversed()))
                .toList();
        Set<Long> capacities = new java.util.HashSet<>();
        for (Ssd ssd : affordable) {
            if (chosen.size() >= Math.max(performanceSlots + 1, limit - 2)) break;
            // 같은 용량의 값싼 상품만 독점하지 않도록 용량별 저가 대표 보존.
            if (capacities.add(getSsdCapacity(ssd))) chosen.putIfAbsent(ssd.getId(), ssd);
        }
        for (Ssd ssd : affordable) {
            if (chosen.size() >= limit) break;
            chosen.putIfAbsent(ssd.getId(), ssd);
        }
        for (Ssd ssd : candidates) {
            if (chosen.size() >= limit) break;
            chosen.putIfAbsent(ssd.getId(), ssd);
        }
        return new ArrayList<>(chosen.values());
    }

    private double getMainboardQualityScore(Mainboard mb) {
        if (mb == null) return 0.0;
        String name = normalize(mb.getName());
        double score;
        if (hasAnyToken(name, "Z890", "Z790", "Z690", "X870", "X670", "X570")) {
            score = 78.0;
        } else if (hasAnyToken(name, "B860", "B760", "B660", "B650", "B550")) {
            score = 68.0;
        } else if (hasAnyToken(name, "H810", "H610", "H510", "H410", "H310",
                "A620", "A520", "A320")) {
            score = 35.0;
        } else {
            score = 57.0; // DB에서 칩셋 확인 불가: 중립 점수
        }

        String pcie = normalize(mb.getPcieType());
        if (pcie.contains("5.0") || pcie.contains("PCIE5")) score += 10.0;
        else if (pcie.contains("4.0") || pcie.contains("PCIE4")) score += 6.0;
        else if (pcie.contains("3.0") || pcie.contains("PCIE3")) score += 2.0;

        long maxClock = mb.getMemoryClock() == null ? 0L : mb.getMemoryClock();
        if (maxClock >= 8000) score += 8.0;
        else if (maxClock >= 7200) score += 6.0;
        else if (maxClock >= 6000) score += 4.0;
        else if (maxClock > 0) score += 2.0;

        if (normalize(mb.getMemoryType()).contains("DDR5")) score += 4.0;
        // 실제 전원부 사양이 없으므로 시리즈 이름의 소폭 보정만 사용한다.
        if (hasAnyToken(name, "AORUS", "TUF", "MORTAR", "박격포", "STEELLEGEND", "토마호크")) score += 5.0;
        if (hasAnyToken(name, "DS3H", "H810", "H610")) score -= 4.0;
        return Math.max(0.0, Math.min(100.0, score));
    }

    private boolean hasAnyToken(String value, String... tokens) {
        if (value == null) return false;
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private double getMainboardCandidateScore(
            Mainboard board, Set<String> preferredBrands) {
        return getMainboardQualityScore(board)
                + (isMainboardPreferred(board, preferredBrands) ? 2.0 : 0.0);
    }

    /**
     * 메모리 규격과 클럭은 별개다. RAM 속도가 보드가 표기한 상한을 넘으면
     * 실행 불가능으로 단정하지 않고, 활용할 수 없는 클럭만큼 추천 점수에 감점한다.
     * 실제 XMP/EXPO 가동 여부는 QVL/CPU 메모리 컨트롤러 확인이 별도로 필요하다.
     */
    private double getBoardAdjustedRamScore(Ram ram, Mainboard board, double baseScore) {
        if (ram == null || board == null
                || ram.getMemoryClock() == null || board.getMemoryClock() == null
                || ram.getMemoryClock() <= 0 || board.getMemoryClock() <= 0) {
            return baseScore;
        }
        long ramClock = ram.getMemoryClock();
        long boardClock = board.getMemoryClock();
        if (ramClock <= boardClock) return baseScore;
        return baseScore * ((double) boardClock / ramClock);
    }

    /** 권장 출력은 만족해야 하며, 대략 10~25%의 출력 여유를 우대한다. */
    private double getPowerHeadroomScore(Gpu gpu, Power power) {
        if (power == null || power.getWattage() == null || power.getWattage() <= 0) {
            return 0.0;
        }
        if (gpu == null || gpu.getRecommendedPower() == null
                || gpu.getRecommendedPower() <= 0) return 70.0;
        double ratio = (double) power.getWattage() / gpu.getRecommendedPower();
        if (ratio < 1.0) return 0.0;
        if (ratio < 1.10) return 70.0 + (ratio - 1.0) * 300.0;
        if (ratio <= 1.25) return 100.0;
        if (ratio <= 1.50) return 100.0 - (ratio - 1.25) * 80.0;
        return 75.0; // 불필요하게 큰 용량이 성능 향상을 의미하지는 않는다.
    }

    /** 케이스의 물리적 장착 여유를 평가한다. 팬/냉각 성능의 대체 지표는 아니다. */
    private double getCaseFitScore(Gpu gpu, Cooler cooler, Case pcCase) {
        if (pcCase == null) return 0.0;
        double total = 0.0;
        double weight = 0.0;
        if (gpu != null && gpu.getGpuLength() != null && gpu.getGpuLength() > 0) {
            weight += 0.6;
            if (pcCase.getGpuLength() == null || pcCase.getGpuLength() <= 0) {
                total += 0.6 * 40.0;
            } else {
                double clearance = pcCase.getGpuLength() - gpu.getGpuLength();
                total += 0.6 * Math.max(0.0, Math.min(100.0, clearance * 100.0 / 30.0));
            }
        }
        if (cooler != null && cooler.getCoolerLength() != null && cooler.getCoolerLength() > 0) {
            weight += 0.4;
            if (pcCase.getCoolerLength() == null || pcCase.getCoolerLength() <= 0) {
                total += 0.4 * 40.0;
            } else {
                double clearance = pcCase.getCoolerLength() - cooler.getCoolerLength();
                total += 0.4 * Math.max(0.0, Math.min(100.0, clearance * 100.0 / 10.0));
            }
        }
        return weight <= 0.0 ? 50.0 : total / weight;
    }

    private double getCaseCandidateScore(Case pcCase) {
        if (pcCase == null) return 0.0;
        // 후보 단계는 대상 GPU/쿨러 확정 전이므로 장착 허용 치수만 약하게 비교한다.
        long gpuLength = pcCase.getGpuLength() == null ? 0L : pcCase.getGpuLength();
        long coolerHeight = pcCase.getCoolerLength() == null ? 0L : pcCase.getCoolerLength();
        return Math.min(100.0, gpuLength / 5.0) * 0.6
                + Math.min(100.0, coolerHeight / 2.0) * 0.4;
    }

    private double getMainboardCandidateAdjustedPrice(
            Mainboard mainboard,
            Set<String> preferredBrands
    ) {

        if (mainboard == null
                || mainboard.getPrice() == null) {

            return Double.MAX_VALUE;
        }

        double adjustedPrice =
                mainboard.getPrice();

        if (isMainboardPreferred(
                mainboard,
                preferredBrands
        )) {

            adjustedPrice *=
                    1.0
                            - BRAND_CANDIDATE_BONUS_RATE;
        }

        return adjustedPrice;
    }

    /*
     * ==========================================
     * ResponseDTO PartDetail 변환
     * ==========================================
     *
     * ResponseDTO.PartDetail 최종 필드 순서:
     * category
     * name
     * price
     * brand
     * chipsetBrand
     * benchScore
     * specSummary
     * productCode
     * productUrl
     * ==========================================
     */

    private ResponseDTO.PartDetail toCpuPartDetail(
            Cpu cpu
    ) {

        return new ResponseDTO.PartDetail(
                "CPU",
                cpu.getName(),
                cpu.getPrice(),
                safeText(cpu.getBrand()),
                "",
                cpu.getBenchScore(),
                buildCpuSpecSummary(cpu),
                cpu.getProductCode(),
                safeText(cpu.getProductUrl())
        );
    }

    private ResponseDTO.PartDetail toGpuPartDetail(
            Gpu gpu
    ) {

        return new ResponseDTO.PartDetail(
                "GPU",
                gpu.getName(),
                gpu.getPrice(),
                safeText(gpu.getBrand()),
                safeText(gpu.getChipsetBrand()),
                gpu.getBenchScore(),
                buildGpuSpecSummary(gpu),
                gpu.getProductCode(),
                safeText(gpu.getProductUrl())
        );
    }

    private ResponseDTO.PartDetail toRamPartDetail(
            Ram ram
    ) {

        return new ResponseDTO.PartDetail(
                "RAM",
                ram.getName(),
                ram.getPrice(),
                "",
                "",
                ram.getBenchScore(),
                buildRamSpecSummary(ram),
                ram.getProductCode(),
                safeText(ram.getProductUrl())
        );
    }

    private ResponseDTO.PartDetail toSsdPartDetail(
            Ssd ssd
    ) {

        return new ResponseDTO.PartDetail(
                "SSD",
                ssd.getName(),
                ssd.getPrice(),
                "",
                "",
                ssd.getBenchScore(),
                buildSsdSpecSummary(ssd),
                ssd.getProductCode(),
                safeText(ssd.getProductUrl())
        );
    }

    private ResponseDTO.PartDetail toMainboardPartDetail(
            Mainboard mainboard
    ) {

        return new ResponseDTO.PartDetail(
                "메인보드",
                mainboard.getName(),
                mainboard.getPrice(),
                safeText(mainboard.getBrand()),
                "",
                null,
                buildMainboardSpecSummary(
                        mainboard
                ),
                mainboard.getProductCode(),
                safeText(
                        mainboard.getProductUrl()
                )
        );
    }

    private ResponseDTO.PartDetail toPowerPartDetail(
            Power power
    ) {

        return new ResponseDTO.PartDetail(
                "파워",
                power.getName(),
                power.getPrice(),
                "",
                "",
                null,
                buildPowerSpecSummary(power),
                power.getProductCode(),
                safeText(power.getProductUrl())
        );
    }

    private ResponseDTO.PartDetail toCasePartDetail(
            Case pcCase
    ) {

        return new ResponseDTO.PartDetail(
                "케이스",
                pcCase.getName(),
                pcCase.getPrice(),
                "",
                "",
                null,
                buildCaseSpecSummary(pcCase),
                pcCase.getProductCode(),
                safeText(pcCase.getProductUrl())
        );
    }

    private ResponseDTO.PartDetail toCoolerPartDetail(
            Cooler cooler
    ) {

        return new ResponseDTO.PartDetail(
                "쿨러",
                cooler.getName(),
                cooler.getPrice(),
                "",
                "",
                null,
                buildCoolerSpecSummary(
                        cooler
                ),
                cooler.getProductCode(),
                safeText(
                        cooler.getProductUrl()
                )
        );
    }

    /*
     * ==========================================
     * specSummary
     * ==========================================
     */

    private String buildCpuSpecSummary(
            Cpu cpu
    ) {

        return joinSpecs(
                cpu.getSocketType(),
                cpu.getMemoryType()
        );
    }

    private String buildGpuSpecSummary(
            Gpu gpu
    ) {

        return joinSpecs(
                gpu.getPcieType(),
                formatNumberUnit(
                        gpu.getRecommendedPower(),
                        "권장파워 ",
                        "W"
                ),
                formatNumberUnit(
                        gpu.getGpuLength(),
                        "길이 ",
                        "mm"
                )
        );
    }

    private String buildRamSpecSummary(
            Ram ram
    ) {

        return joinSpecs(
                ram.getMemoryType(),
                formatNumberUnit(
                        ram.getMemoryClock(),
                        "",
                        "MHz"
                ),
                formatNumberUnit(
                        ram.getCapacity(),
                        "",
                        "GB"
                ),
                formatNumberUnit(
                        ram.getModuleCount(),
                        "",
                        "개 모듈"
                )
        );
    }

    private String buildSsdSpecSummary(
            Ssd ssd
    ) {

        return joinSpecs(
                formatNumberUnit(
                        ssd.getCapacity(),
                        "",
                        "GB"
                )
        );
    }

    private String buildMainboardSpecSummary(
            Mainboard mainboard
    ) {

        return joinSpecs(
                mainboard.getSocketType(),
                mainboard.getMemoryType(),
                mainboard.getPcieType(),
                mainboard.getSize(),
                formatNumberUnit(
                        mainboard.getMemoryClock(),
                        "최대 ",
                        "MHz"
                )
        );
    }

    private String buildPowerSpecSummary(
            Power power
    ) {

        return joinSpecs(
                power.getSize(),
                formatNumberUnit(
                        power.getWattage(),
                        "",
                        "W"
                )
        );
    }

    private String buildCaseSpecSummary(
            Case pcCase
    ) {

        return joinSpecs(
                pcCase.getSize(),
                formatNumberUnit(
                        pcCase.getGpuLength(),
                        "GPU 최대 ",
                        "mm"
                ),
                formatNumberUnit(
                        pcCase.getCoolerLength(),
                        "쿨러 최대 ",
                        "mm"
                )
        );
    }

    private String buildCoolerSpecSummary(
            Cooler cooler
    ) {

        return joinSpecs(
                cooler.getSocketType(),
                formatNumberUnit(
                        cooler.getCoolerLength(),
                        "높이 ",
                        "mm"
                )
        );
    }

    private String formatNumberUnit(
            Number value,
            String prefix,
            String suffix
    ) {

        if (value == null) {
            return "";
        }

        return safeText(prefix)
                + value
                + safeText(suffix);
    }

    private String joinSpecs(
            String... values
    ) {

        List<String> validSpecs =
                new ArrayList<>();

        if (values == null) {
            return "";
        }

        for (String value : values) {

            if (value == null
                    || value.isBlank()) {

                continue;
            }

            validSpecs.add(
                    value.trim()
            );
        }

        return String.join(
                " / ",
                validSpecs
        );
    }

    private String safeText(
            String value
    ) {

        return value != null
                ? value
                : "";
    }

    /*
     * ==========================================
     * 추천 탐색 타임아웃
     * ==========================================
     */

    private boolean isTimedOut(
            long deadlineNanos
    ) {

        return System.nanoTime()
                >= deadlineNanos;
    }

    /*
     * ==========================================
     * TOP 3 관리
     *
     * 핵심 조합 기준:
     * CPU + GPU + RAM
     *
     * 동일 핵심 조합은 TOP 3에 하나만 남긴다.
     * SSD / Mainboard / Power / Case / Cooler만 다른 조합이 여러 개
     * TOP 3를 차지하는 현상을 방지한다.
     *
     * 동일 핵심 조합에서는:
     * 1) 점수가 더 높은 조합
     * 2) 점수가 같으면 총액이 더 낮은 조합
     * 을 유지한다.
     * ==========================================
     */

    private void addTopCandidate(
            PriorityQueue<RecommendationCandidate> pool,
            RecommendationCandidate candidate,
            Comparator<? super RecommendationCandidate> qualityOrder
    ) {
        RecommendationCandidate sameCore = pool.stream()
                .filter(previous -> isSameCoreCombination(previous, candidate))
                .findFirst().orElse(null);

        if (sameCore != null) {
            if (qualityOrder.compare(candidate, sameCore) > 0) {
                pool.remove(sameCore);
                pool.offer(candidate);
            }
            return;
        }

        if (pool.size() < SEARCH_TOP_POOL_COUNT) {
            pool.offer(candidate);
            return;
        }

        RecommendationCandidate weakest = pool.peek();
        if (weakest != null && qualityOrder.compare(candidate, weakest) > 0) {
            pool.poll();
            pool.offer(candidate);
        }
    }

    /**
     * 정규화된 성능 점수로 특화 부품, 반대 부품, 종합 품질을 동시에 평가한다.
     * 절대 벤치마크 * 1000을 사용하면 반대 부품 성능을 무시하므로 제거한다.
     */
    /** 최종 답변까지 실제 예산·소켓·RAM·GPU·파워·케이스·쿨러 적합성을 보존한다. */
    private boolean validFinalRecommendation(RecommendationCandidate c, long budget,
            MinimumBudgetCalculator.Policy policy) {
        if (c == null || c.totalPrice() > budget || c.totalPrice() <= 0) return false;
        PricePriorityOptimizer.Build build = new PricePriorityOptimizer.Build(
                c.cpu(), c.gpu(), c.ram(), c.ssd(), c.mainboard(), c.power(),
                c.pcCase(), c.cooler());
        return build.totalPrice() == c.totalPrice()
                && PricePriorityOptimizer.isCompatible(build, policy);
    }

    private boolean isDistinctFromSelected(List<RecommendationCandidate> selected,
            RecommendationCandidate candidate) {
        for (RecommendationCandidate previous : selected) {
            if (isSamePerformanceCombination(previous, candidate)) return false;
        }
        return true;
    }

    /**
     * 최고 GPU를 균형형이 독점하면 GPU 강화형을 만들 수 없다.
     * 성능이 비슷한 조합 중 약간 낮은 GPU를 사용하는 균형형이 있을 때만 교체한다.
     * 이때 6점은 전략의 차이를 확보하기 위한 최대 절충 폭으로, 최종 호환성 필터가 아니다.
     * GPU가 아닌 다른 부품 성능이 현저히 낮다면 원래 최고 균형형을 유지한다.
     */
    private RecommendationCandidate rebalanceJointAnchorForGpuUpgrade(
            RecommendationCandidate balanced, List<RecommendationCandidate> candidates,
            boolean office) {
        if (office || balanced == null || candidates.isEmpty()) return balanced;
        long maxGpu = candidates.stream().mapToLong(c -> getGpuBenchmarkScore(c.gpu()))
                .max().orElse(0);
        System.out.println("[Strategy] currentGpu=" + getGpuBenchmarkScore(balanced.gpu())
                + " / maxAvailableGpu=" + maxGpu);
        if (getGpuBenchmarkScore(balanced.gpu()) < maxGpu) return balanced;
        RecommendationCandidate alternative = null;
        double bestAlternativeScore = candidates.stream()
                .filter(c -> getGpuBenchmarkScore(c.gpu()) < getGpuBenchmarkScore(balanced.gpu()))
                .mapToDouble(RecommendationCandidate::score).max().orElse(-1.0);
        System.out.println("[Strategy] gpu-anchor candidate bestDifferentGpuScore="
                + String.format(java.util.Locale.ROOT, "%.2f", bestAlternativeScore)
                + " / chosenBalancedScore="
                + String.format(java.util.Locale.ROOT, "%.2f", balanced.score()));
        long maximumCpu = candidates.stream()
                .mapToLong(candidate -> getCpuBenchmarkScore(candidate.cpu()))
                .max().orElse(0L);
        for (RecommendationCandidate c : candidates) {
            // CPU 강화형 여지까지 보존한다.
            if (getCpuBenchmarkScore(c.cpu()) >= maximumCpu) continue;
            if (getGpuBenchmarkScore(c.gpu()) >= getGpuBenchmarkScore(balanced.gpu())) continue;
            if (c.score() < balanced.score() - 6.0) continue;
            // GPU 이외의 기준이 지나치게 약해지는 경우를 방지한다.
            if (getCpuBenchmarkScore(c.cpu()) < getCpuBenchmarkScore(balanced.cpu()) * 0.85)
                continue;
            if (alternative == null || c.score() > alternative.score()) alternative = c;
        }
        if (alternative != null) {
            System.out.println("[Strategy] 균형형 GPU 분화: "
                    + getGpuBenchmarkScore(balanced.gpu()) + " -> "
                    + getGpuBenchmarkScore(alternative.gpu())
                    + " / 균형점수 " + String.format(java.util.Locale.ROOT, "%.2f", alternative.score())
                    + " / 강화형에 상위 GPU 배정");
            return alternative;
        }
        return balanced;
    }

    /** 최고 성능의 CPU/GPU 모두에 업그레이드 여지가 있는 기준을 선호한다. */
    private RecommendationCandidate chooseJointBalancedAnchor(
            List<RecommendationCandidate> candidates, boolean office) {
        if (candidates.isEmpty()) return null;
        long bestCpu = candidates.stream().mapToLong(c -> getCpuBenchmarkScore(c.cpu())).max().orElse(0);
        long bestGpu = candidates.stream().mapToLong(c -> getGpuBenchmarkScore(c.gpu())).max().orElse(0);
        long bestRam = candidates.stream().mapToLong(c -> getRamCapacity(c.ram())).max().orElse(0);
        double maxValue = Double.NEGATIVE_INFINITY;
        double maxBalancedScore = candidates.stream()
                .mapToDouble(RecommendationCandidate::score).max().orElse(0);
        RecommendationCandidate best = null;
        // 전체 순위가 아니라 실제로 검증된 조합의 CPU/GPU 성능 분포를 사용한다.
        for (RecommendationCandidate c : candidates) {
            // 균형형 자체가 더 낮은 총점을 받는 상황은 방지한다.
            // 최고 종합점수와 근접한 후보들 사이에서만 향상 여지를 비교한다.
            if (c.score() + 1.0 < maxBalancedScore) continue;
            double bonus = 0.0;
            if (getCpuBenchmarkScore(c.cpu()) < bestCpu) bonus += 1.8;
            if (!office && getGpuBenchmarkScore(c.gpu()) < bestGpu) bonus += 1.8;
            if (office && getRamCapacity(c.ram()) < bestRam) bonus += 0.8;
            double value = c.score() + bonus;
            if (value > maxValue + 1e-8) { maxValue = value; best = c; }
        }
        return best;
    }

    /** 하드 커트라인 대신 CPU·GPU 업그레이드 우선순위를 가산점으로 반영. */
    private double effectiveFocusValue(RecommendationStrategy strategy,
            RecommendationCandidate c, RecommendationCandidate balanced,
            ScoreContext context, boolean office) {
        double total = strategyScore(c.score(), c.cpu(), c.gpu(), c.ram(), c.ssd(),
                strategy, context, office);
        if (balanced == null) return total;
        if (office && strategy == RecommendationStrategy.GPU_FOCUSED) {
            // SSD만 4배로 올리느라 CPU가 70% 하락하는 조합을 방지하는 연속형 평가.
            double cpuRatio = (double) getCpuBenchmarkScore(c.cpu())
                    / Math.max(1L, getCpuBenchmarkScore(balanced.cpu()));
            double loss = Math.max(0, 1.0 - cpuRatio);
            double capacityGain = Math.max(0, getRamCapacity(c.ram()) - getRamCapacity(balanced.ram()));
            double ssdGain = Math.max(0, getSsdCapacity(c.ssd()) - getSsdCapacity(balanced.ssd()));
            return c.score() * 0.75 + getNormalizedRamScore(c.ram(), context) * 0.18
                    + getNormalizedSsdScore(c.ssd(), context) * 0.07
                    + Math.min(3.0, capacityGain * 0.10 + ssdGain / 1200.0)
                    - loss * 15.0;
        }
        double previousPrimary = strategy == RecommendationStrategy.GPU_FOCUSED
                ? getNormalizedGpuScore(balanced.gpu(), context)
                : getNormalizedCpuScore(balanced.cpu(), context);
        double currentPrimary = strategy == RecommendationStrategy.GPU_FOCUSED
                ? getNormalizedGpuScore(c.gpu(), context)
                : getNormalizedCpuScore(c.cpu(), context);
        // 주력 성능이 높아졌을 때만 보너스를 준다. 같으면 둘째로 종합성능을 비교한다.
        double improvement = Math.max(0, currentPrimary - previousPrimary);
        double decline = Math.max(0, previousPrimary - currentPrimary);
        if (strategy == RecommendationStrategy.GPU_FOCUSED) {
            // GPU 강화형은 GPU 향상이 1차 목표다. CPU/RAM/SSD는 보조 점수로 비교.
            // 업그레이드에 비싼 GPU가 필요하더라도 종합점수만으로 놓치지 않게 한다.
            return total + improvement * 2.5 - decline * 0.8;
        }
        return total + improvement * 0.65 - decline * 0.4;
    }

    private String focusName(RecommendationStrategy strategy, RecommendationCandidate c,
            RecommendationCandidate balanced, boolean office) {
        if (strategy == RecommendationStrategy.CPU_FOCUSED) {
            return balanced != null && getCpuBenchmarkScore(c.cpu()) > getCpuBenchmarkScore(balanced.cpu())
                    ? "CPU 강화형" : "CPU 중심 대안";
        }
        if (office) {
            if (balanced != null && getRamCapacity(c.ram()) > getRamCapacity(balanced.ram()))
                return "메모리 강화형";
            if (balanced != null && getSsdCapacity(c.ssd()) > getSsdCapacity(balanced.ssd()))
                return "저장공간 강화형";
            return "사무용 구성 대안";
        }
        return balanced != null && getGpuBenchmarkScore(c.gpu()) > getGpuBenchmarkScore(balanced.gpu())
                ? "GPU 강화형" : "GPU 중심 대안";
    }

    private double strategyScore(
            double balancedScore, Cpu cpu, Gpu gpu, Ram ram, Ssd ssd,
            RecommendationStrategy strategy, ScoreContext context,
            boolean officeWithoutDiscreteGpu
    ) {
        if (strategy == RecommendationStrategy.BALANCED) return balancedScore;
        if (officeWithoutDiscreteGpu && strategy == RecommendationStrategy.GPU_FOCUSED) {
            return balancedScore * 0.35
                    + getNormalizedRamScore(ram, context) * 0.40
                    + getNormalizedSsdScore(ssd, context) * 0.25;
        }
        double primary = strategy == RecommendationStrategy.GPU_FOCUSED
                ? getNormalizedGpuScore(gpu, context) : getNormalizedCpuScore(cpu, context);
        double secondary = strategy == RecommendationStrategy.GPU_FOCUSED
                ? getNormalizedCpuScore(cpu, context) : getNormalizedGpuScore(gpu, context);
        return balancedScore * 0.35 + primary * 0.50 + secondary * 0.15;
    }

    /**
     * 특화 부품이 균형형보다 높으면 우선, 같으면 차선, 낮아도 후보에서 제거하지 않는다.
     * 가혹한 성능 비율 하한이나 RAM/SSD 하한을 적용하지 않는다.
     */
    private int simpleFocusTier(RecommendationStrategy strategy,
                                RecommendationCandidate candidate,
                                RecommendationCandidate balanced,
                                boolean officeWithoutDiscreteGpu) {
        if (strategy == RecommendationStrategy.BALANCED || balanced == null) return 0;
        if (strategy == RecommendationStrategy.CPU_FOCUSED) {
            long current = getCpuBenchmarkScore(candidate.cpu());
            long reference = getCpuBenchmarkScore(balanced.cpu());
            return current > reference ? 0 : current == reference ? 1 : 2;
        }
        if (officeWithoutDiscreteGpu) {
            if (getRamCapacity(candidate.ram()) > getRamCapacity(balanced.ram())
                    || getSsdCapacity(candidate.ssd()) > getSsdCapacity(balanced.ssd())) return 0;
            return 1;
        }
        long current = getGpuBenchmarkScore(candidate.gpu());
        long reference = getGpuBenchmarkScore(balanced.gpu());
        return current > reference ? 0 : current == reference ? 1 : 2;
    }

    /** 점수가 높으면서 특화 부품 우선순위도 더 좋은 조합을 선택한다. */
    private boolean isBetterSimpleStrategyCandidate(
            RecommendationCandidate candidate, RecommendationCandidate current,
            RecommendationStrategy strategy, RecommendationCandidate baseline,
            ScoreContext context, boolean officeWithoutDiscreteGpu) {
        int candidateTier = simpleFocusTier(strategy, candidate, baseline, officeWithoutDiscreteGpu);
        int currentTier = simpleFocusTier(strategy, current, baseline, officeWithoutDiscreteGpu);
        if (candidateTier != currentTier) return candidateTier < currentTier;
        double candidateScore = strategyScore(candidate.score(), candidate.cpu(), candidate.gpu(),
                candidate.ram(), candidate.ssd(), strategy, context, officeWithoutDiscreteGpu);
        double currentScore = strategyScore(current.score(), current.cpu(), current.gpu(),
                current.ram(), current.ssd(), strategy, context, officeWithoutDiscreteGpu);
        if (Math.abs(candidateScore - currentScore) > 1e-7) return candidateScore > currentScore;
        return candidate.totalPrice() < current.totalPrice();
    }

    /** 추천 1/2/3이 CPU/GPU만 동일하고 주변 부품만 다른 상황을 방지한다. */
    private boolean isSamePerformanceCombination(
            RecommendationCandidate a, RecommendationCandidate b
    ) {
        return isSameEntityId(a.cpu().getId(), b.cpu().getId())
                && gpuChipsetKey(a.gpu()).equals(gpuChipsetKey(b.gpu()));
    }

    /*
     * ==========================================
     * 핵심 조합 동일 여부
     *
     * SSD / Mainboard / Power / Case / Cooler는 제외한다.
     * ==========================================
     */

    private boolean isSameCoreCombination(
            RecommendationCandidate a, RecommendationCandidate b) {
        if (a == null || b == null) return false;
        return isSameEntityId(a.cpu().getId(), b.cpu().getId())
                && gpuChipsetKey(a.gpu()).equals(gpuChipsetKey(b.gpu()))
                && getRamCapacity(a.ram()) == getRamCapacity(b.ram())
                && getSsdCapacity(a.ssd()) == getSsdCapacity(b.ssd());
    }

    private String gpuChipsetKey(Gpu gpu) {
        if (gpu == null) return "INTEGRATED";
        String name = normalize(gpu.getName());
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(RTX|RX|ARC)([0-9]{3,5})(TI|SUPER|XTX|XT)?")
                .matcher(name);
        if (matcher.find()) return matcher.group();
        // 모델 식별에 실패하면 서로 다른 GPU를 잘못 합치지 않는다.
        return "GPU_ID:" + gpu.getId();
    }

    private boolean isSameEntityId(
            Long a,
            Long b
    ) {

        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.equals(b);
    }

    /*
     * ==========================================
     * 후보 우선순위 비교
     *
     * 점수가 높을수록 우선
     * 같은 점수면 총액이 낮을수록 우선
     * ==========================================
     */

    private boolean isBetterCandidate(
            RecommendationCandidate candidate,
            RecommendationCandidate current
    ) {

        int scoreCompare =
                Double.compare(
                        candidate.score(),
                        current.score()
                );

        if (scoreCompare > 0) {
            return true;
        }

        if (scoreCompare < 0) {
            return false;
        }

        return candidate.totalPrice()
                < current.totalPrice();
    }

    private List<ResponseDTO.PartDetail> createRecommendedPartDetails(RecommendationCandidate candidate) {
        List<ResponseDTO.PartDetail> parts = new ArrayList<>();
        parts.add(toCpuPartDetail(candidate.cpu()));
        if (candidate.gpu() != null) {
            parts.add(toGpuPartDetail(candidate.gpu()));
        }
        parts.add(toRamPartDetail(candidate.ram()));
        parts.add(toSsdPartDetail(candidate.ssd()));
        parts.add(toMainboardPartDetail(candidate.mainboard()));
        parts.add(toPowerPartDetail(candidate.power()));
        parts.add(toCasePartDetail(candidate.pcCase()));
        parts.add(toCoolerPartDetail(candidate.cooler()));
        return parts;
    }

    /**
     * DB에 내장 그래픽 컬럼이 없어 모델명으로 확인 가능한 경우만 허용한다.
     * 불확실한 모델은 내장 그래픽 견적에서 제외한다.
     */
    private boolean hasIntegratedGraphics(Cpu cpu) {
        if (cpu == null || cpu.getName() == null) {
            return false;
        }
        String name = cpu.getName().toUpperCase(java.util.Locale.ROOT);
        String socket = cpu.getSocketType() == null ? "" : cpu.getSocketType().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("인텔") || name.contains("INTEL")) {
            if (name.contains("제온") || name.contains("XEON")) {
                return false;
            }
            return !name.matches(".*\\b\\d{3,5}(?:KF|F)\\b.*");
        }
        if (name.contains("라이젠") || name.contains("RYZEN")) {
            if (name.matches(".*\\b\\d{4,5}F\\b.*")) {
                return false;
            }
            if (name.matches(".*\\b\\d{4,5}(?:G|GE|GT)\\b.*")) {
                return true;
            }
            // AM5 데스크톱 Ryzen 7000/9000 시리즈: F 모델 제외.
            return socket.equals("AMD5") &&
                    name.matches(".*\\b(?:7|9)\\d{3}(?:X3D\\d?|X|XT)?\\b.*");
        }
        return false;
    }

    private boolean hasValidPrice(Long price) {

        return price != null
                && price > 0;
    }

    /*
     * ==========================================
     * 호환성
     * ==========================================
     */

    private boolean isSocketCompatible(
            String cpuSocket,
            String mbSocket
    ) {

        if (cpuSocket == null
                || mbSocket == null) {

            return false;
        }

        String cpu =
                normalizeSocket(cpuSocket);

        String mb =
                normalizeSocket(mbSocket);

        if (cpu.isBlank()
                || mb.isBlank()) {

            return false;
        }

        if (cpu.equals(mb)) {
            return true;
        }

        return cpu.contains(mb)
                || mb.contains(cpu);
    }

    private boolean isCpuCoolerCompatible(
            Cpu cpu,
            Cooler cooler
    ) {

        if (cpu.getSocketType() == null
                || cooler.getSocketType() == null) {

            return false;
        }

        String cpuSocket =
                normalizeSocket(
                        cpu.getSocketType()
                );

        String coolerSocket =
                normalizeSocket(
                        cooler.getSocketType()
                );

        if (cpuSocket.isBlank()
                || coolerSocket.isBlank()) {

            return false;
        }

        if (coolerSocket.contains(cpuSocket)
                || cpuSocket.contains(coolerSocket)) {

            return true;
        }

        if (isIntel115xCompatible(
                cpuSocket,
                coolerSocket
        )) {

            return true;
        }

        return isAmdSocketCompatible(
                cpuSocket,
                coolerSocket
        );
    }

    private boolean isIntel115xCompatible(
            String cpuSocket,
            String coolerSocket
    ) {

        boolean cpuIs115x =
                cpuSocket.equals("1150")
                        || cpuSocket.equals("1151")
                        || cpuSocket.equals("1151V2")
                        || cpuSocket.equals("1155")
                        || cpuSocket.equals("1156");

        boolean coolerSupports115x =
                coolerSocket.contains("115X")
                        || coolerSocket.contains("1150")
                        || coolerSocket.contains("1151")
                        || coolerSocket.contains("1151V2")
                        || coolerSocket.contains("1155")
                        || coolerSocket.contains("1156");

        return cpuIs115x
                && coolerSupports115x;
    }

    private boolean isAmdSocketCompatible(
            String cpuSocket,
            String coolerSocket
    ) {

        if (cpuSocket.equals("AMD4")) {

            return coolerSocket.contains("AM4")
                    || coolerSocket.contains("AMD4");
        }

        if (cpuSocket.equals("AMD5")) {

            return coolerSocket.contains("AM5")
                    || coolerSocket.contains("AMD5");
        }

        return false;
    }

    private boolean isMemoryCompatible(
            String ramMemoryType,
            String mbMemoryType
    ) {

        if (ramMemoryType == null
                || mbMemoryType == null) {

            return true;
        }

        String ram =
                normalize(ramMemoryType);

        String mb =
                normalize(mbMemoryType);

        return ram.contains(mb)
                || mb.contains(ram);
    }

    private boolean isGpuCaseCompatible(
            Gpu gpu,
            Case pcCase
    ) {

        if (gpu == null) {
            return true;
        }
        if (gpu.getGpuLength() == null
                || pcCase.getGpuLength() == null) {

            return true;
        }

        return gpu.getGpuLength()
                <= pcCase.getGpuLength();
    }

    private boolean isCoolerCaseCompatible(
            Cooler cooler,
            Case pcCase
    ) {

        if (cooler.getCoolerLength() == null
                || pcCase.getCoolerLength() == null) {

            return true;
        }

        return cooler.getCoolerLength()
                <= pcCase.getCoolerLength();
    }

    /*
     * ==========================================
     * CPU별 메인보드 후보 선택
     *
     * 1) CPU 소켓과 호환되는 메인보드만 사용
     * 2) 고성능 CPU + 엔트리 칩셋 조합 제외
     * 3) 남은 후보는 지원 사양이 높은 순서로 탐색
     *
     * 이 규칙은 소켓 호환 여부가 아니라
     * 추천 조합의 밸런스를 위한 적합성 규칙이다.
     * ==========================================
     */

    /*
     * ==========================================
     * CPU별 쿨러 후보 생성
     *
     * 고성능 CPU:
     * - CPU 소켓 호환
     * - 기존 coolerBudget에 가까운 가격 우선
     *
     * 일반 CPU:
     * - CPU 소켓 호환
     * - 기존처럼 낮은 가격 우선
     *
     * TDP / 냉각성능 데이터가 현재 DB에 없으므로
     * 임의의 절대 가격 하한을 만들지 않는다.
     * ==========================================
     */

    /*
     * ==========================================
     * 메인보드별 RAM 후보 생성
     *
     * 1) 메인보드 memory_type과 호환되는 RAM만 선택
     * 2) 해당 호환 RAM 집합 안에서 benchmark / capacity 범위 계산
     * 3) 기존 RAM 점수식(50/25/25)에 보드 지원클럭 감점을 더해 정렬
     * 4) 최대 30개 사용
     *
     * 이 방식으로 DDR4와 DDR5가 후보 선정 단계에서
     * 서로 경쟁하여 한쪽 타입이 후보 30개를 독식하는 문제를 막는다.
     * ==========================================
     */

    private List<Ram> getRamCandidatesForMainboard(
            Mainboard mainboard,
            List<Ram> ramPool
    ) {

        if (mainboard == null
                || ramPool == null
                || ramPool.isEmpty()) {

            return List.of();
        }

        List<Ram> compatibleRams =
                ramPool.stream()
                        .filter(
                                ram ->
                                        isMemoryCompatible(
                                                ram.getMemoryType(),
                                                mainboard.getMemoryType()
                                        )
                        )
                        .toList();

        if (compatibleRams.isEmpty()) {
            return List.of();
        }

        return compatibleRams.stream()
                .sorted(Comparator.comparingDouble((Ram item) -> getRamBenchmarkScore(item)).reversed()
                        .thenComparingLong(Ram::getPrice))
                // 여기서 50개만 남기면 낮은 가격의 동급 성능 제품을 놓칠 수 있다.
                .toList();
    }

    private List<Cooler> getCoolerCandidatesForCpu(Cpu cpu, List<Cooler> coolerPool,
                                                    long coolerBudget) {
        if (cpu == null || coolerPool == null || coolerPool.isEmpty()) return List.of();
        return coolerPool.stream()
                .filter(cooler -> RecommendationQualityRules.coolerSuitable(cpu, cooler))
                .sorted(Comparator.comparingInt(RecommendationQualityRules::coolerTier).reversed()
                        .thenComparingLong(Cooler::getPrice))
                .limit(40)
                .toList();
    }

    private List<Mainboard> getMainboardCandidatesForCpu(
            Cpu cpu,
            List<Mainboard> mainboardPool,
            Set<String> preferredBrands
    ) {

        if (cpu == null
                || mainboardPool == null
                || mainboardPool.isEmpty()) {

            return List.of();
        }

        return mainboardPool.stream()
                .filter(mb -> BuildRules.cpuMainboardBalanced(cpu, mb))
                .sorted(Comparator.comparingLong(Mainboard::getPrice).reversed())
                .limit(30)
                .toList();
    }

    /*
     * ==========================================
     * CPU - 메인보드 구성 적합성
     *
     * 고성능 CPU에는 엔트리급 칩셋을 제외한다.
     *
     * Intel 엔트리 계열:
     * H310 / H410 / H510 / H610 / H810
     *
     * AMD 엔트리 계열:
     * A320 / A520 / A620
     *
     * 예:
     * i7-14700KF + H610 -> 추천 후보 제외
     * i7-14700KF + B760 -> 허용
     *
     * 실제 소켓 호환성은 별도 메서드에서 계속 검사한다.
     * ==========================================
     */

    private boolean isHighPerformanceCpu(
            Cpu cpu
    ) {

        if (cpu == null
                || cpu.getName() == null) {

            return false;
        }

        String name =
                normalize(
                        cpu.getName()
                );

        return name.contains("코어I7")
                || name.contains("COREI7")
                || name.contains("코어I9")
                || name.contains("COREI9")
                || name.contains("코어울트라7")
                || name.contains("COREULTRA7")
                || name.contains("울트라7")
                || name.contains("ULTRA7")
                || name.contains("코어울트라9")
                || name.contains("COREULTRA9")
                || name.contains("울트라9")
                || name.contains("ULTRA9")
                || name.contains("라이젠7")
                || name.contains("RYZEN7")
                || name.contains("라이젠9")
                || name.contains("RYZEN9");
    }

    /*
     * ==========================================
     * GPU별 파워 후보 선택
     *
     * 권장 파워 이상의 제품만 남기고 10~25% 출력 여유를 우대한다.
     * 출력 여유 점수가 같으면 저렴한 제품 우선.
     * ==========================================
     */

    private List<Power> getPowerCandidatesForGpu(Gpu gpu, List<Power> powerPool) {
        if (powerPool == null || powerPool.isEmpty()) return List.of();
        long required = gpu == null || gpu.getRecommendedPower() == null
                ? 0L : gpu.getRecommendedPower();
        return powerPool.stream()
                .filter(power -> power.getWattage() != null && power.getWattage() >= required)
                .filter(RecommendationQualityRules::powerCandidate)
                .sorted(Comparator.comparingInt(RecommendationQualityRules::powerTier).reversed()
                        .thenComparingLong(Power::getPrice))
                .limit(60)
                .toList();
    }

    private boolean isPowerCompatible(
            Gpu gpu,
            Power power
    ) {

        if (gpu == null) {
            return power.getWattage() != null && power.getWattage() > 0;
        }
        if (gpu.getRecommendedPower() == null
                || power.getWattage() == null) {

            return true;
        }

        return power.getWattage()
                >= gpu.getRecommendedPower();
    }

    /*
     * ==========================================
     * 문자열 정규화
     * ==========================================
     */

    private String normalize(String value) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toUpperCase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
    }

    private String normalizeSocket(String value) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toUpperCase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .replace("소켓", "")
                .replace("지원", "")
                .replace("INTEL", "")
                .replace("인텔", "")
                .replace("LGA", "")
                .replace("SOCKET", "");
    }

    /*
     * ==========================================
     * 정규화 범위 생성
     * ==========================================
     */

    private ScoreContext createScoreContext(
            List<Cpu> cpus,
            List<Gpu> gpus,
            List<Ram> rams,
            List<Ssd> ssds
    ) {

        ScoreRange cpuBench =
                createRange(
                        cpus.stream()
                                .mapToLong(
                                        this::getCpuBenchmarkScore
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        ScoreRange gpuBench =
                createRange(
                        gpus.stream()
                                .mapToLong(
                                        this::getGpuBenchmarkScore
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        ScoreRange ramBench =
                createRange(
                        rams.stream()
                                .mapToDouble(
                                        this::getRamBenchmarkScore
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        ScoreRange ramCapacity =
                createRange(
                        rams.stream()
                                .mapToLong(
                                        this::getRamCapacity
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        ScoreRange ssdBench =
                createRange(
                        ssds.stream()
                                .mapToLong(
                                        this::getSsdBenchmarkScore
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        ScoreRange ssdCapacity =
                createRange(
                        ssds.stream()
                                .mapToLong(
                                        this::getSsdCapacity
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        return new ScoreContext(
                cpuBench,
                gpuBench,
                ramBench,
                ramCapacity,
                ssdBench,
                ssdCapacity
        );
    }

    private ScoreRange createRange(
            long[] values
    ) {

        if (values == null
                || values.length == 0) {

            return new ScoreRange(
                    0.0,
                    0.0
            );
        }

        long min = values[0];
        long max = values[0];

        for (long value : values) {

            if (value < min) {
                min = value;
            }

            if (value > max) {
                max = value;
            }
        }

        return new ScoreRange(
                min,
                max
        );
    }

    private ScoreRange createRange(
            double[] values
    ) {

        if (values == null
                || values.length == 0) {

            return new ScoreRange(
                    0.0,
                    0.0
            );
        }

        double min = values[0];
        double max = values[0];

        for (double value : values) {

            if (value < min) {
                min = value;
            }

            if (value > max) {
                max = value;
            }
        }

        return new ScoreRange(
                min,
                max
        );
    }

    /*
     * ==========================================
     * 0 ~ 100 일반 정규화
     * ==========================================
     */

    private double normalizeScore(
            double value,
            ScoreRange range
    ) {

        if (value <= 0
                || range == null
                || range.max() <= 0) {

            return 0.0;
        }

        if (range.max() == range.min()) {
            return 100.0;
        }

        double result =
                ((value - range.min())
                        / (range.max() - range.min()))
                        * 100.0;

        if (result < 0) {
            return 0.0;
        }

        if (result > 100) {
            return 100.0;
        }

        return result;
    }

    /*
     * ==========================================
     * Capacity 로그 정규화
     *
     * 예:
     * 8 → 16 → 32 → 64 → 128
     *
     * 단순 선형이 아니라 배수 증가 기준 평가
     * ==========================================
     */

    private double normalizeLogCapacity(
            long value,
            ScoreRange range
    ) {

        if (value <= 0
                || range == null
                || range.min() <= 0
                || range.max() <= 0) {

            return 0.0;
        }

        if (range.max() == range.min()) {
            return 100.0;
        }

        double logValue =
                Math.log(value)
                        / Math.log(2);

        double logMin =
                Math.log(range.min())
                        / Math.log(2);

        double logMax =
                Math.log(range.max())
                        / Math.log(2);

        if (logMax == logMin) {
            return 100.0;
        }

        double result =
                ((logValue - logMin)
                        / (logMax - logMin))
                        * 100.0;

        if (result < 0) {
            return 0.0;
        }

        if (result > 100) {
            return 100.0;
        }

        return result;
    }

    /*
     * ==========================================
     * CPU 점수
     * ==========================================
     */

    private double getNormalizedCpuScore(
            Cpu cpu,
            ScoreContext context
    ) {

        return normalizeScore(
                getCpuBenchmarkScore(cpu),
                context.cpuBench()
        );
    }

    /*
     * ==========================================
     * GPU 점수
     * ==========================================
     */

    private double getNormalizedGpuScore(
            Gpu gpu,
            ScoreContext context
    ) {

        return normalizeScore(
                getGpuBenchmarkScore(gpu),
                context.gpuBench()
        );
    }

    /*
     * ==========================================
     * RAM 최종 점수
     *
     * Benchmark   50%
     * Capacity    25%
     * ModuleCount 25%
     *
     * Capacity는 log2 정규화
     * ==========================================
     */

    private double getNormalizedRamScore(
            Ram ram,
            ScoreContext context
    ) {

        return calculateRamScore(
                ram,
                context.ramBench(),
                context.ramCapacity()
        );
    }

    /*
     * ==========================================
     * RAM 실제 점수 계산
     * ==========================================
     */

    private double calculateRamScore(
            Ram ram,
            ScoreRange benchRange,
            ScoreRange capacityRange
    ) {

        double weightedScore = 0.0;
        double totalWeight = 0.0;

        double benchmark =
                getRamBenchmarkScore(ram);

        long capacity =
                getRamCapacity(ram);

        long moduleCount =
                getRamModuleCount(ram);

        /*
         * Benchmark
         */
        if (benchmark > 0) {

            double benchScore =
                    normalizeScore(
                            benchmark,
                            benchRange
                    );

            weightedScore +=
                    benchScore
                            * RAM_BENCHMARK_WEIGHT;

            totalWeight +=
                    RAM_BENCHMARK_WEIGHT;
        }

        /*
         * Capacity
         */
        if (capacity > 0) {

            double capacityScore =
                    normalizeLogCapacity(
                            capacity,
                            capacityRange
                    );

            weightedScore +=
                    capacityScore
                            * RAM_CAPACITY_WEIGHT;

            totalWeight +=
                    RAM_CAPACITY_WEIGHT;
        }

        /*
         * Module 구성
         */
        if (moduleCount > 0) {

            double moduleScore =
                    getRamModuleConfigurationScore(
                            ram
                    );

            weightedScore +=
                    moduleScore
                            * RAM_MODULE_WEIGHT;

            totalWeight +=
                    RAM_MODULE_WEIGHT;
        }

        /*
         * 일부 데이터가 없다면
         * 존재하는 요소만으로 다시 100% 환산
         */
        if (totalWeight <= 0) {
            return 0.0;
        }

        return weightedScore
                / totalWeight;
    }

    /*
     * ==========================================
     * RAM 모듈 구성 점수
     *
     * 1개 = 40
     * 2개 = 100
     * 4개 = 80
     * 8개 = 60
     *
     * 일반적인 데스크톱에서는
     * 2 DIMM 구성을 가장 우대
     * ==========================================
     */

    private double getRamModuleConfigurationScore(
            Ram ram
    ) {

        long moduleCount =
                getRamModuleCount(ram);

        if (moduleCount <= 0) {
            return 0.0;
        }

        if (moduleCount == 1) {
            return 40.0;
        }

        if (moduleCount == 2) {
            return 100.0;
        }

        if (moduleCount == 4) {
            return 80.0;
        }

        if (moduleCount == 8) {
            return 60.0;
        }

        return 50.0;
    }

    /*
     * ==========================================
     * SSD 점수
     *
     * 기존 로직 유지
     *
     * Benchmark 70%
     * Capacity  30%
     * ==========================================
     */

    private double getNormalizedSsdScore(
            Ssd ssd,
            ScoreContext context
    ) {

        double benchScore =
                normalizeScore(
                        getSsdBenchmarkScore(ssd),
                        context.ssdBench()
                );

        double capacityScore =
                normalizeScore(
                        getSsdCapacity(ssd),
                        context.ssdCapacity()
                );

        if (getSsdBenchmarkScore(ssd) <= 0) {

            return capacityScore;
        }

        if (getSsdCapacity(ssd) <= 0) {

            return benchScore;
        }

        return benchScore * 0.70
                + capacityScore * 0.30;
    }

    /*
     * ==========================================
     * 최종 조합 점수
     * ==========================================
     */

    private double calculateTotalScore(
            Cpu cpu,
            Gpu gpu,
            Ram ram,
            Ssd ssd,
            Mainboard mainboard,
            Power power,
            Case pcCase,
            Cooler cooler,
            String usage,
            ScoreContext context,
            Set<String> preferredBrands,
            long targetCoolerBudget
    ) {

        double cpuScore =
                getNormalizedCpuScore(
                        cpu,
                        context
                );

        double gpuScore =
                getNormalizedGpuScore(
                        gpu,
                        context
                );

        double ramScore = getBoardAdjustedRamScore(
                ram,
                mainboard,
                getNormalizedRamScore(ram, context)
        );

        double ssdScore =
                getNormalizedSsdScore(
                        ssd,
                        context
                );

        double baseScore;

        /*
         * GAMING
         *
         * GPU 45%
         * CPU 30%
         * RAM 15%
         * SSD 10%
         */
        if ("GAMING".equals(usage)) {

            baseScore =
                    gpuScore * 0.45
                            + cpuScore * 0.30
                            + ramScore * 0.15
                            + ssdScore * 0.10;
        }

        /*
         * HIGH END
         *
         * CPU 35%
         * GPU 30%
         * RAM 20%
         * SSD 15%
         */
        else if ("HIGH_END".equals(usage)) {

            baseScore =
                    cpuScore * 0.35
                            + gpuScore * 0.30
                            + ramScore * 0.20
                            + ssdScore * 0.15;
        }

        /*
         * OFFICE
         *
         * CPU 40%
         * RAM 30%
         * SSD 25%
         * GPU 5%
         */
        else if ("OFFICE".equals(usage)) {
            baseScore = gpu == null
                    ? cpuScore * 0.45 + ramScore * 0.30 + ssdScore * 0.25
                    : cpuScore * 0.40 + ramScore * 0.30
                    + ssdScore * 0.25 + gpuScore * 0.05;
        }

        else {

            baseScore =
                    gpuScore * 0.40
                            + cpuScore * 0.30
                            + ramScore * 0.15
                            + ssdScore * 0.15;
        }

        double brandBonus =
                calculateBrandPreferenceBonus(
                        cpu,
                        gpu,
                        mainboard,
                        preferredBrands
                );

        /*
         * 전체 조합 점수 (각 항목 0~100)
         * CPU/GPU/RAM/SSD 용도별 성능: 76%
         * 메인보드 지원 사양: 12%
         * 파워 출력 여유/제품명 효율 등급: 7%
         * 케이스 장착 여유: 4%
         * 쿨러 예산 균형: 1%
         * (품질을 확인할 컬럼이 없는 항목은 보수적으로 평가)
         */
        // 벤치마크가 있는 부품의 점수가 최우선이다.
        // 벤치마크가 없는 4개 부품은 같은 성능일 때 고가 제품을 소폭 우대한다.
        double supportPrice = mainboard.getPrice() + power.getPrice()
                + pcCase.getPrice() + cooler.getPrice();
        double reference = Math.max(1.0, targetCoolerBudget * 12.0);
        double supportPriceScore = 100.0 * supportPrice / (supportPrice + reference);
        double balanceScore = baseScore * 0.95 + supportPriceScore * 0.05;

        double supplyRiskPenalty = 0.0;
        String ssdName = normalize(ssd == null ? null : ssd.getName());
        if (ssdName.contains("해외구매")) supplyRiskPenalty = 1.0;
        else if (ssdName.contains("병행수입")) supplyRiskPenalty = 0.6;
        // 국내 A/S 가능 여부는 실제 제품 상세 페이지 확인이 필요하다.
        return Math.max(0.0, Math.min(100.0,
                balanceScore + brandBonus * 0.20 - supplyRiskPenalty));
    }

    /*
     * ==========================================
     * RAM 후보 정렬 점수
     *
     * 최종 RAM 점수와 동일한 산식 사용
     *
     * Benchmark   50%
     * Capacity    25%
     * ModuleCount 25%
     * ==========================================
     */

    private double getRamCandidateScore(
            Ram ram,
            ScoreRange benchRange,
            ScoreRange capacityRange
    ) {

        return calculateRamScore(
                ram,
                benchRange,
                capacityRange
        );
    }

    /*
     * ==========================================
     * SSD 후보 정렬용 점수
     * ==========================================
     */

    private long getSsdCandidateScore(
            Ssd ssd
    ) {

        long benchmark =
                getSsdBenchmarkScore(ssd);

        long capacity =
                getSsdCapacity(ssd);

        return benchmark
                + capacity * 10;
    }

    /*
     * ==========================================
     * 데이터 값 추출
     * ==========================================
     */

    private long getCpuBenchmarkScore(
            Cpu cpu
    ) {

        if (cpu.getBenchScore() != null
                && cpu.getBenchScore() > 0) {

            return cpu.getBenchScore();
        }

        return 0;
    }

    private long getGpuBenchmarkScore(
            Gpu gpu
    ) {

        if (gpu == null) {
            return 0;
        }
        if (gpu.getBenchScore() != null
                && gpu.getBenchScore() > 0) {

            return gpu.getBenchScore();
        }

        return 0;
    }

    /*
     * RAM benchmark는
     * 11.4 / 13.0 / 21.3 등 소수점이 존재하므로
     * double로 유지
     */
    private double getRamBenchmarkScore(
            Ram ram
    ) {

        if (ram.getBenchScore() != null
                && ram.getBenchScore() > 0) {

            return ram.getBenchScore().doubleValue();
        }

        return 0.0;
    }

    private long getSsdBenchmarkScore(
            Ssd ssd
    ) {

        if (ssd.getBenchScore() != null
                && ssd.getBenchScore() > 0) {

            return ssd.getBenchScore();
        }

        return 0;
    }

    private long getRamCapacity(
            Ram ram
    ) {

        if (ram.getCapacity() != null
                && ram.getCapacity() > 0) {

            return ram.getCapacity();
        }

        return 0;
    }

    private long getRamModuleCount(
            Ram ram
    ) {

        if (ram.getModuleCount() != null
                && ram.getModuleCount() > 0) {

            return ram.getModuleCount();
        }

        return 0;
    }

    private long getSsdCapacity(
            Ssd ssd
    ) {

        if (ssd.getCapacity() != null
                && ssd.getCapacity() > 0) {

            return ssd.getCapacity();
        }

        return 0;
    }

    /*
     * ==========================================
     * 용도 이름
     * ==========================================
     */

    private String getUsageLabel(
            String usage
    ) {

        if ("GAMING".equals(usage)) {
            return "게이밍";
        }

        if ("HIGH_END".equals(usage)) {
            return "하이엔드용";
        }

        if ("OFFICE".equals(usage)) {
            return "사무용";
        }

        return "게이밍";
    }

    /*
     * ==========================================
     * Records
     * ==========================================
     */

    private record SupportOption(Cooler cooler, Power power, Case pcCase,
                                 long price, double quality) {}

    private record ScoreRange(
            double min,
            double max
    ) {
    }

    private record ScoreContext(
            ScoreRange cpuBench,
            ScoreRange gpuBench,
            ScoreRange ramBench,
            ScoreRange ramCapacity,
            ScoreRange ssdBench,
            ScoreRange ssdCapacity
    ) {
    }

    private record RecommendationCandidate(
            Cpu cpu,
            Gpu gpu,
            Ram ram,
            Ssd ssd,
            Mainboard mainboard,
            Power power,
            Case pcCase,
            Cooler cooler,
            long totalPrice,
            double score
    ) {
    }
}
