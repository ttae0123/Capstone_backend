package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.RequestDTO;
import com.example.capstone_backend.dto.ResponseDTO;
import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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

    private static final int MAX_RECOMMENDATION_COUNT = 3;
    private static final long RECOMMENDATION_TIMEOUT_MS = 30_000L;

    /*
     * CPU / GPU 목표 예산 최대 허용 배수
     * 기존 1.7 -> 1.3
     */
    private static final double CPU_BUDGET_MAX_MULTIPLIER = 1.30;
    private static final double GPU_BUDGET_MAX_MULTIPLIER = 1.30;

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

        long minimumBudget = minimumBudgetService.minimumFor(usage);
        if (budget < minimumBudget) {
            throw new IllegalArgumentException(
                    usage + " 견적의 현재 최소 예산은 " + String.format("%,d", minimumBudget) + "원입니다."
            );
        }
        MinimumBudgetCalculator.Policy minimumPolicy = MinimumBudgetCalculator.Policy.of(usage);

        BudgetRatioService.Allocation ratio = budgetRatioService.resolve(usage, budget);
        boolean officeWithoutDiscreteGpu = "OFFICE".equals(usage) && ratio.gpu() == 0.0;
        System.out.println("[BudgetRatio] 기준 예산 = " + ratio.referenceBudget());

        /*
         * ==========================================
         * DB 조회
         * ==========================================
         */

        System.out.println();
        System.out.println("======================================");
        System.out.println("[DB] 부품 데이터 조회 시작");
        System.out.println("======================================");

        List<Cpu> cpus = cpuRepository.findAll();
        List<Gpu> gpus = gpuRepository.findAll();
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

        long cpuBudget = (long) (budget * ratio.cpu());
        long gpuBudget = (long) (budget * ratio.gpu());
        long ramBudget = (long) (budget * ratio.ram());
        long ssdBudget = (long) (budget * ratio.ssd());
        long mbBudget = (long) (budget * ratio.mainboard());
        long powerBudget = (long) (budget * ratio.power());
        long caseBudget = (long) (budget * ratio.pcCase());
        long coolerBudget = (long) (budget * ratio.cooler());

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

        System.out.println(
                "[CPU MAX CANDIDATE PRICE] "
                        + (long) (cpuBudget * CPU_BUDGET_MAX_MULTIPLIER)
        );

        System.out.println(
                "[GPU MAX CANDIDATE PRICE] "
                        + (long) (gpuBudget * GPU_BUDGET_MAX_MULTIPLIER)
        );

        /*
         * ==========================================
         * CPU 후보
         * ==========================================
         */

        List<Cpu> cpuCandidates = cpus.stream()
                .filter(cpu -> hasValidPrice(cpu.getPrice()))
                .filter(cpu -> cpu.getBenchScore() != null && cpu.getBenchScore() >= minimumPolicy.cpuBench())
                .filter(cpu -> !officeWithoutDiscreteGpu || hasIntegratedGraphics(cpu))
                .filter(cpu -> cpu.getPrice() <= cpuBudget * CPU_BUDGET_MAX_MULTIPLIER)
                .sorted(
                        (a, b) -> Double.compare(
                                getCpuCandidateRankingScore(
                                        b,
                                        preferredBrands
                                ),
                                getCpuCandidateRankingScore(
                                        a,
                                        preferredBrands
                                )
                        )
                )
                .limit(30)
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
                .filter(gpu -> gpu.getPrice() <= gpuBudget * GPU_BUDGET_MAX_MULTIPLIER)
                .sorted(
                        (a, b) -> Double.compare(
                                getGpuCandidateRankingScore(
                                        b,
                                        preferredBrands
                                ),
                                getGpuCandidateRankingScore(
                                        a,
                                        preferredBrands
                                )
                        )
                )
                .limit(30)
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
                .filter(ram -> ram.getPrice() <= ramBudget * 1.8)
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
                .filter(ssd -> ssd.getBenchScore() != null && ssd.getBenchScore() >= minimumPolicy.ssdBench())
                .filter(ssd -> ssd.getPrice() <= ssdBudget * 1.8)
                .sorted(
                        (a, b) -> Long.compare(
                                getSsdCandidateScore(b),
                                getSsdCandidateScore(a)
                        )
                )
                .limit(30)
                .toList();

        ssdCandidates = relaxBudgetLimitWhenEmpty(
                "SSD", ssdCandidates, ssds,
                ssd -> ssd.getCapacity() != null && ssd.getCapacity() >= minimumPolicy.ssdGb()
                        && ssd.getBenchScore() != null && ssd.getBenchScore() >= minimumPolicy.ssdBench(),
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
         * 4) 남은 후보 중 저렴한 순서로 최대 30개 사용
         *
         * 물리적 호환성 체크와는 별개로
         * 추천 품질을 위한 구성 적합성 필터이다.
         * ==========================================
         */

        List<Mainboard> mainboardPool = mbs.stream()
                .filter(mb -> hasValidPrice(mb.getPrice()))
                .filter(mb -> mb.getPrice() <= mbBudget * 1.8)
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
         * 1) 예산 안의 파워 전체를 Pool로 구성
         * 2) 각 GPU의 recommended_power 이상만 선택
         * 3) 권장 파워와의 차이가 작은 순서로 정렬
         * 4) 같은 용량이면 더 저렴한 제품 우선
         *
         * 즉, GPU가 750W를 요구하면
         * 1600W가 아니라 750W/800W/850W처럼
         * 필요한 용량에 가까운 파워부터 탐색한다.
         * ==========================================
         */

        List<Power> powerPool = powers.stream()
                .filter(power -> hasValidPrice(power.getPrice()))
                .filter(power -> power.getPrice() <= powerBudget * 2.0)
                .filter(power -> power.getWattage() != null)
                .filter(power -> power.getWattage() >= minimumPolicy.minWattage())
                .filter(BuildRules::standardAtxPower)
                .toList();

        powerPool = relaxBudgetLimitWhenEmpty(
                "POWER", powerPool, powers,
                power -> power.getWattage() != null
                        && power.getWattage() >= minimumPolicy.minWattage()
                        && BuildRules.standardAtxPower(power),
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
                .filter(pcCase -> pcCase.getPrice() <= caseBudget * 2.0)
                .sorted(Comparator.comparingLong(Case::getPrice))
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
                .filter(cooler -> cooler.getPrice() <= Math.max(coolerBudget * 2.0, 25_000.0))
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

        for (Mainboard mb : recommendationMainboards.values()) {

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

        for (Cpu cpu : cpuCandidates) {

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

        for (Gpu gpu : gpuCandidates) {

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
                                + "W / 가장 가까운 후보="
                                + firstPower.getWattage()
                                + "W / 후보수="
                                + gpuPowers.size()
                );
            }
        }
        System.out.println("[CASE 후보] " + caseCandidates.size());
        System.out.println("[COOLER POOL] " + coolerPool.size());

        for (Cpu cpu : cpuCandidates) {

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

        ScoreContext scoreContext = createScoreContext(
                cpuCandidates,
                gpuCandidates,
                ramCandidatesForScoring,
                ssdCandidates
        );

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
        PriorityQueue<RecommendationCandidate> topCandidates =
                new PriorityQueue<>(
                        Comparator
                                .comparingDouble(
                                        RecommendationCandidate::score
                                )
                                .thenComparing(
                                        Comparator.comparingLong(
                                                RecommendationCandidate::totalPrice
                                        ).reversed()
                                )
                );

        int failCpuMb = 0;
        int failRamMb = 0;
        int failGpuCase = 0;
        int failCpuCooler = 0;
        int failCoolerCase = 0;
        int failPower = 0;
        int failBudget = 0;
        int success = 0;

        long algorithmStartNanos = System.nanoTime();
        long algorithmDeadlineNanos =
                algorithmStartNanos
                        + RECOMMENDATION_TIMEOUT_MS * 1_000_000L;

        boolean timedOut = false;

        System.out.println();
        System.out.println("======================================");
        System.out.println("[Algorithm] 조합 탐색 시작");
        System.out.println("======================================");

        /*
         * ==========================================
         * 기존 탐색 구조 유지
         * ==========================================
         */

        List<Gpu> searchGpuCandidates = officeWithoutDiscreteGpu
                ? java.util.Collections.singletonList(null)
                : gpuCandidates;

        searchLoop:
        for (Cpu cpu : cpuCandidates) {

            if (isTimedOut(algorithmDeadlineNanos)) {
                timedOut = true;
                break;
            }

            List<Mainboard> cpuMainboardCandidates =
                    mainboardCandidatesByCpu.getOrDefault(
                            cpu.getId(),
                            List.of()
                    );

            List<Cooler> cpuCoolerCandidates =
                    coolerCandidatesByCpu.getOrDefault(
                            cpu.getId(),
                            List.of()
                    );

            for (Mainboard mb : cpuMainboardCandidates) {

                if (isTimedOut(algorithmDeadlineNanos)) {
                    timedOut = true;
                    break searchLoop;
                }

                if (!isSocketCompatible(
                        cpu.getSocketType(),
                        mb.getSocketType()
                )) {

                    failCpuMb++;
                    continue;
                }

                long cpuMbPrice =
                        cpu.getPrice()
                                + mb.getPrice();

                if (cpuMbPrice > budget) {

                    failBudget++;
                    continue;
                }

                List<Ram> mbRamCandidates =
                        ramCandidatesByMainboard.getOrDefault(
                                mb.getId(),
                                List.of()
                        );

                if (mbRamCandidates.isEmpty()) {
                    failRamMb++;
                    continue;
                }

                for (Cooler cooler : cpuCoolerCandidates) {

                    if (isTimedOut(algorithmDeadlineNanos)) {
                        timedOut = true;
                        break searchLoop;
                    }

                    if (!isCpuCoolerCompatible(cpu, cooler)) {

                        failCpuCooler++;
                        continue;
                    }

                    long cpuMbCoolerPrice =
                            cpuMbPrice
                                    + cooler.getPrice();

                    if (cpuMbCoolerPrice > budget) {

                        failBudget++;
                        continue;
                    }

                    for (Ram ram : mbRamCandidates) {

                        if (isTimedOut(algorithmDeadlineNanos)) {
                            timedOut = true;
                            break searchLoop;
                        }

                        if (!isMemoryCompatible(
                                ram.getMemoryType(),
                                mb.getMemoryType()
                        )) {

                            failRamMb++;
                            continue;
                        }

                        long cpuMbCoolerRamPrice =
                                cpuMbCoolerPrice
                                        + ram.getPrice();

                        if (cpuMbCoolerRamPrice > budget) {

                            failBudget++;
                            continue;
                        }

                        for (Gpu gpu : searchGpuCandidates) {

                            if (isTimedOut(algorithmDeadlineNanos)) {
                                timedOut = true;
                                break searchLoop;
                            }

                            long cpuMbCoolerRamGpuPrice =
                                    cpuMbCoolerRamPrice
                                            + (gpu == null ? 0L : gpu.getPrice());

                            if (cpuMbCoolerRamGpuPrice > budget) {

                                failBudget++;
                                continue;
                            }

                            List<Power> gpuPowerCandidates = gpu == null
                                    ? officePowerCandidates
                                    : powerCandidatesByGpu.getOrDefault(gpu.getId(), List.of());

                            if (gpuPowerCandidates.isEmpty()) {
                                failPower++;
                                continue;
                            }

                            for (Case pcCase : caseCandidates) {

                                if (isTimedOut(algorithmDeadlineNanos)) {
                                    timedOut = true;
                                    break searchLoop;
                                }

                                if (!BuildRules.boardCase(mb, pcCase)
                                        || !isGpuCaseCompatible(gpu, pcCase)) {

                                    failGpuCase++;
                                    continue;
                                }

                                if (!isCoolerCaseCompatible(
                                        cooler,
                                        pcCase
                                )) {

                                    failCoolerCase++;
                                    continue;
                                }

                                long cpuMbCoolerRamGpuCasePrice =
                                        cpuMbCoolerRamGpuPrice
                                                + pcCase.getPrice();

                                if (cpuMbCoolerRamGpuCasePrice > budget) {

                                    failBudget++;
                                    continue;
                                }

                                for (Power power : gpuPowerCandidates) {

                                    if (isTimedOut(algorithmDeadlineNanos)) {
                                        timedOut = true;
                                        break searchLoop;
                                    }

                                    if (!BuildRules.power(gpu, power, minimumPolicy.minWattage())) {

                                        failPower++;
                                        continue;
                                    }

                                    long cpuMbCoolerRamGpuCasePowerPrice =
                                            cpuMbCoolerRamGpuCasePrice
                                                    + power.getPrice();

                                    if (cpuMbCoolerRamGpuCasePowerPrice > budget) {

                                        failBudget++;
                                        continue;
                                    }

                                    for (Ssd ssd : ssdCandidates) {

                                        if (isTimedOut(algorithmDeadlineNanos)) {
                                            timedOut = true;
                                            break searchLoop;
                                        }

                                        long total =
                                                cpuMbCoolerRamGpuCasePowerPrice
                                                        + ssd.getPrice();

                                        if (total > budget) {

                                            failBudget++;
                                            continue;
                                        }

                                        double score =
                                                calculateTotalScore(
                                                        cpu,
                                                        gpu,
                                                        ram,
                                                        ssd,
                                                        mb,
                                                        usage,
                                                        scoreContext,
                                                        preferredBrands
                                                );

                                        RecommendationCandidate candidate =
                                                new RecommendationCandidate(
                                                        cpu,
                                                        gpu,
                                                        ram,
                                                        ssd,
                                                        mb,
                                                        power,
                                                        pcCase,
                                                        cooler,
                                                        total,
                                                        score
                                                );

                                        addTopCandidate(
                                                topCandidates,
                                                candidate
                                        );

                                        success++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        long algorithmElapsedMs =
                (System.nanoTime() - algorithmStartNanos)
                        / 1_000_000L;

        System.out.println();
        System.out.println("======================================");
        System.out.println("[Algorithm] 조합 탐색 종료");
        System.out.println("======================================");

        System.out.println("[FAIL] CPU-메인보드 호환 실패 = " + failCpuMb);
        System.out.println("[FAIL] RAM-메인보드 호환 실패 = " + failRamMb);
        System.out.println("[FAIL] GPU-케이스 호환 실패 = " + failGpuCase);
        System.out.println("[FAIL] CPU-쿨러 소켓 실패 = " + failCpuCooler);
        System.out.println("[FAIL] 쿨러-케이스 높이 실패 = " + failCoolerCase);
        System.out.println("[FAIL] 파워 용량 부족 실패 = " + failPower);
        System.out.println("[FAIL] 총 예산 초과 실패 = " + failBudget);
        System.out.println("[SUCCESS] 성공 조합 수 = " + success);
        System.out.println("[TOP] 저장된 상위 후보 수 = " + topCandidates.size());
        System.out.println("[TOP] 핵심 조합 중복 제거 = CPU + GPU + RAM");
        System.out.println("[TIME] 탐색 시간 = " + algorithmElapsedMs + "ms");
        System.out.println("[TIMEOUT] 제한 시간 = " + RECOMMENDATION_TIMEOUT_MS + "ms");

        if (timedOut) {
            System.out.println(
                    "[TIMEOUT] 제한 시간 초과로 탐색을 중단하고 "
                            + "현재까지의 상위 후보를 반환합니다."
            );
        }

        if (topCandidates.isEmpty()) {
            return fallbackToMinimumBuild(
                    usage, budget,
                    new MinimumBudgetCalculator.Parts(cpus, gpus, mbs, rams, ssds, powers, cases, coolers),
                    timedOut ? "조합 탐색 시간 제한으로 성공 후보 없음" : "조합 탐색 결과 유효한 후보 없음"
            );
        }

        /*
         * ==========================================
         * 최종 정렬
         * ==========================================
         */

        List<RecommendationCandidate> finalCandidates =
                new ArrayList<>(topCandidates);

        finalCandidates.sort(
                (a, b) ->
                        Double.compare(
                                b.score(),
                                a.score()
                        )
        );

        /*
         * ==========================================
         * Response
         * ==========================================
         */

        List<ResponseDTO.Recommendation> results = finalCandidates.stream()
                .map(candidate -> new ResponseDTO.Recommendation(
                        getUsageLabel(usage) + " 최적 조합",
                        candidate.totalPrice(),
                        createRecommendedPartDetails(candidate)
                ))
                .toList();

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

            double ramScore =
                    getNormalizedRamScore(
                            c.ram(),
                            scoreContext
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

        RecommendationCandidate candidate = new RecommendationCandidate(
                cpu, gpu, ram, ssd, mainboard, power, pcCase, cooler, min.amount(), 0.0
        );
        System.out.println("[Fallback] 최소 조건 조합 반환: " + min.amount() + "원");
        return new ResponseDTO.ResultList(List.of(
                new ResponseDTO.Recommendation(
                        getUsageLabel(usage) + " 최소 조건 조합",
                        min.amount(),
                        createRecommendedPartDetails(candidate)
                )
        ));
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

    private double getCpuCandidateRankingScore(
            Cpu cpu,
            Set<String> preferredBrands
    ) {

        double score =
                getCpuBenchmarkScore(
                        cpu
                );

        if (isCpuPreferred(
                cpu,
                preferredBrands
        )) {

            score *=
                    1.0
                            + BRAND_CANDIDATE_BONUS_RATE;
        }

        return score;
    }

    private double getGpuCandidateRankingScore(
            Gpu gpu,
            Set<String> preferredBrands
    ) {

        double score =
                getGpuBenchmarkScore(
                        gpu
                );

        if (isGpuPreferred(
                gpu,
                preferredBrands
        )) {

            score *=
                    1.0
                            + BRAND_CANDIDATE_BONUS_RATE;
        }

        return score;
    }

    /*
     * 메인보드는 기존에 저가순으로 후보를 구성하므로,
     * 선호 브랜드 제품은 정렬용 가격만 5% 낮춰 계산한다.
     *
     * 실제 제품 가격이나 총 견적 금액은 절대 변경하지 않는다.
     */

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
            PriorityQueue<RecommendationCandidate> topCandidates,
            RecommendationCandidate candidate
    ) {

        RecommendationCandidate sameCoreCandidate =
                topCandidates.stream()
                        .filter(
                                existing ->
                                        isSameCoreCombination(
                                                existing,
                                                candidate
                                        )
                        )
                        .findFirst()
                        .orElse(null);

        /*
         * 이미 동일한 핵심 조합이 TOP 후보에 있다면
         * 더 좋은 대표 조합만 남긴다.
         */
        if (sameCoreCandidate != null) {

            if (isBetterCandidate(
                    candidate,
                    sameCoreCandidate
            )) {

                topCandidates.remove(
                        sameCoreCandidate
                );

                topCandidates.offer(
                        candidate
                );
            }

            return;
        }

        /*
         * 아직 TOP 3가 가득 차지 않았다면
         * 새로운 핵심 조합을 추가한다.
         */
        if (topCandidates.size()
                < MAX_RECOMMENDATION_COUNT) {

            topCandidates.offer(
                    candidate
            );

            return;
        }

        /*
         * TOP 3가 가득 찬 경우
         * 현재 최하위 후보보다 좋은 경우에만 교체한다.
         */
        RecommendationCandidate lowestCandidate =
                topCandidates.peek();

        if (lowestCandidate != null
                && isBetterCandidate(
                candidate,
                lowestCandidate
        )) {

            topCandidates.poll();

            topCandidates.offer(
                    candidate
            );
        }
    }

    /*
     * ==========================================
     * 핵심 조합 동일 여부
     *
     * SSD / Mainboard / Power / Case / Cooler는 제외한다.
     * ==========================================
     */

    private boolean isSameCoreCombination(
            RecommendationCandidate a,
            RecommendationCandidate b
    ) {

        if (a == null || b == null) {
            return false;
        }
        if ((a.gpu() == null) != (b.gpu() == null)) {
            return false;
        }

        return isSameEntityId(
                a.cpu().getId(),
                b.cpu().getId()
        )
                && isSameEntityId(
                a.gpu() == null ? null : a.gpu().getId(),
                b.gpu() == null ? null : b.gpu().getId()
        )
                && isSameEntityId(
                a.ram().getId(),
                b.ram().getId()
        );
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
     * 3) 남은 후보는 가격이 낮은 순서로 탐색
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
     * 3) 기존 RAM 점수식(60/25/15)으로 정렬
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

        ScoreRange benchRange =
                createRange(
                        compatibleRams.stream()
                                .mapToDouble(
                                        this::getRamBenchmarkScore
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        ScoreRange capacityRange =
                createRange(
                        compatibleRams.stream()
                                .mapToLong(
                                        this::getRamCapacity
                                )
                                .filter(value -> value > 0)
                                .toArray()
                );

        return compatibleRams.stream()
                .sorted(
                        (a, b) -> Double.compare(
                                getRamCandidateScore(
                                        b,
                                        benchRange,
                                        capacityRange
                                ),
                                getRamCandidateScore(
                                        a,
                                        benchRange,
                                        capacityRange
                                )
                        )
                )
                .limit(30)
                .toList();
    }

    private List<Cooler> getCoolerCandidatesForCpu(
            Cpu cpu,
            List<Cooler> coolerPool,
            long coolerBudget
    ) {

        if (cpu == null
                || coolerPool == null
                || coolerPool.isEmpty()) {

            return List.of();
        }

        if (isHighPerformanceCpu(cpu)) {

            return coolerPool.stream()
                    .filter(cooler ->
                            isCpuCoolerCompatible(
                                    cpu,
                                    cooler
                            )
                    )
                    .sorted(
                            Comparator
                                    .comparingLong(
                                            (Cooler cooler) ->
                                                    Math.abs(
                                                            cooler.getPrice()
                                                                    - coolerBudget
                                                    )
                                    )
                                    .thenComparingLong(
                                            Cooler::getPrice
                                    )
                    )
                    .limit(20)
                    .toList();
        }

        return coolerPool.stream()
                .filter(cooler ->
                        isCpuCoolerCompatible(
                                cpu,
                                cooler
                        )
                )
                .sorted(
                        Comparator.comparingLong(
                                Cooler::getPrice
                        )
                )
                .limit(20)
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
                .filter(mb -> BuildRules.cpuMainboard(cpu, mb))
                .sorted(
                        Comparator
                                .comparingDouble(
                                        (Mainboard mb) ->
                                                getMainboardCandidateAdjustedPrice(
                                                        mb,
                                                        preferredBrands
                                                )
                                )
                                .thenComparingLong(
                                        Mainboard::getPrice
                                )
                )
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

    private boolean isCpuMainboardBalanceCompatible(
            Cpu cpu,
            Mainboard mainboard
    ) {

        if (cpu == null
                || mainboard == null
                || cpu.getName() == null
                || mainboard.getName() == null) {

            return true;
        }

        if (!isHighPerformanceCpu(cpu)) {
            return true;
        }

        return !isEntryLevelMainboard(mainboard);
    }

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

    private boolean isEntryLevelMainboard(
            Mainboard mainboard
    ) {

        if (mainboard == null
                || mainboard.getName() == null) {

            return false;
        }

        String name =
                normalize(
                        mainboard.getName()
                );

        return name.contains("H310")
                || name.contains("H410")
                || name.contains("H510")
                || name.contains("H610")
                || name.contains("H810")
                || name.contains("A320")
                || name.contains("A520")
                || name.contains("A620");
    }

    /*
     * ==========================================
     * GPU별 파워 후보 선택
     *
     * 권장 파워 이상의 제품만 남기고
     * 권장 파워와 가장 가까운 용량부터 선택한다.
     *
     * 동일 Wattage에서는 저렴한 제품 우선.
     * ==========================================
     */

    private List<Power> getPowerCandidatesForGpu(
            Gpu gpu,
            List<Power> powerPool
    ) {

        if (powerPool == null
                || powerPool.isEmpty()) {

            return List.of();
        }

        Long recommendedPower = gpu == null ? null : gpu.getRecommendedPower();

        /*
         * 권장 파워 정보가 없는 경우에는
         * 가장 낮은 정상 Wattage부터 탐색한다.
         */
        if (recommendedPower == null
                || recommendedPower <= 0) {

            return powerPool.stream()
                    .filter(power -> power.getWattage() != null)
                    .filter(power -> power.getWattage() > 0)
                    .sorted(
                            Comparator
                                    .comparingLong(
                                            (Power power) ->
                                                    power.getWattage()
                                    )
                                    .thenComparingLong(
                                            Power::getPrice
                                    )
                    )
                    .limit(20)
                    .toList();
        }

        return powerPool.stream()
                .filter(power -> power.getWattage() != null)
                .filter(power ->
                        power.getWattage()
                                >= recommendedPower
                )
                .sorted(
                        Comparator
                                .comparingLong(
                                        (Power power) ->
                                                power.getWattage()
                                                        - recommendedPower
                                )
                                .thenComparingLong(
                                        Power::getPrice
                                )
                )
                .limit(20)
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
            String usage,
            ScoreContext context,
            Set<String> preferredBrands
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

        double ramScore =
                getNormalizedRamScore(
                        ram,
                        context
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
         * VIDEO EDITING / WORK
         *
         * CPU 35%
         * GPU 30%
         * RAM 20%
         * SSD 15%
         */
        else if ("VIDEO_EDITING".equals(usage)
                || "WORK".equals(usage)) {

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
         * 기본 성능 점수는 0~100 범위이며,
         * 브랜드 선호 가산점을 더한 뒤 최종 점수 역시 100을 넘지 않게 제한한다.
         */
        return Math.min(
                100.0,
                baseScore + brandBonus
        );
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

        if ("WORK".equals(usage)) {
            return "전문 작업용";
        }

        if ("VIDEO_EDITING".equals(usage)) {
            return "영상 편집용";
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
