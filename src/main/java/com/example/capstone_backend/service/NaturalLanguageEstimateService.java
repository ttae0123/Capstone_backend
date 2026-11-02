package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.ParsedRequest;
import com.example.capstone_backend.dto.GameClassification;
import com.example.capstone_backend.dto.RequestDTO;
import com.example.capstone_backend.dto.ResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/**
 * 자연어 입력의 정책 해석 전담. 기존 EstimateService의 탐색/점수 계산은 변경하지 않는다.
 * - Gemini: 사용자가 언급한 예산·용도·게임명 추출, 미등록 게임의 유사 등급 추정
 * - 이 서비스: DB 커트라인 기반 분류, 기본 예산 적용, 충돌 선택지 계산
 */
@Service
public class NaturalLanguageEstimateService {
    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageEstimateService.class);
    private static final long OFFICE_DISPLAY_MAX = 900_000L;
    private static final long GAMING_DISPLAY_MAX = 4_400_000L;
    private static final long HIGH_END_DEFAULT = 6_000_000L;
    private static final long BUDGET_STEP = 100_000L;
    private static final double GAME_BUDGET_BUFFER = 1.07; // 이전에 제안한 기본값: 100/150/250/330만원
    private static final double MIN_GAME_CONFIDENCE = 0.65;
    private static final long CACHE_TTL_SECONDS = 24 * 3600L;
    private static final int CACHE_LIMIT = 300;
    private static final Set<String> REFERENCE_GAMES = Set.of("LOL", "PUBG", "CYBERPUNK", "CRIMSON_DESERT");
    private final ConcurrentHashMap<String, CachedGame> gameCache = new ConcurrentHashMap<>();

    private final OpenAiService openAiService;
    private final MinimumBudgetService minimumBudgetService;
    private final EstimateService estimateService;

    public NaturalLanguageEstimateService(OpenAiService openAiService,
                                          MinimumBudgetService minimumBudgetService,
                                          EstimateService estimateService) {
        this.openAiService = openAiService;
        this.minimumBudgetService = minimumBudgetService;
        this.estimateService = estimateService;
    }


    public record Option(String id, String label, String description, String usage, long budget) {}
    public record Decision(String status, Plan plan, String prompt, List<Option> options) {}
    public record Plan(String usage, long budget, String intent, String gameName, String referenceGame,
                       List<String> warnings) {}
    public record ConfirmRequest(String usage, Long budget) {}
    private record CachedGame(String reference, double confidence, String reason, Instant validUntil) {}

    public Decision resolve(String userInput) {
        // 쉬운 요청은 로컬에서 처리하고, 조건이 불명확할 때만 Gemini에 위임한다.
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("자연어 견적 요청 내용을 입력해주세요.");
        }
        if (userInput.length() > 1500) {
            throw new IllegalArgumentException("요청 문장은 1,500자 이하로 입력해주세요.");
        }
        ParsedRequest parsed = SimpleNaturalLanguageParser.tryParse(userInput);
        if (parsed == null) {
            log.info("[자연어] 규칙 분석 불확실 → Gemini 조건 해석 호출");
            parsed = openAiService.parseUserInput(userInput);
        } else {
            log.info("[자연어] 규칙 분석 성공 → Gemini 조건 해석 생략: usage={}, budget={}, game={}",
                    parsed.getUsage(), parsed.getBudget(), parsed.getGameName());
        }
        List<String> warnings = new ArrayList<>();
        String intent = parsed.getUsage();
        String gameName = parsed.getGameName() == null ? "" : parsed.getGameName().trim();
        long statedBudget = parsed.getBudget();
        boolean hasBudget = statedBudget > 0;
        boolean hasGame = !gameName.isBlank();
        boolean isGameIntent = hasGame || "GAMING".equals(intent);
        boolean isExplicitEngineUsage = Set.of("OFFICE", "GAMING", "HIGH_END").contains(intent);

        String referenceGame = "";
        long suggestedGameBudget = 0;
        long gameMinimum = 0;
        if (hasGame) {
            referenceGame = recognizedGame(gameName);
            if (referenceGame.isBlank()) {
                CachedGame approximate = classifyCached(gameName);
                if (approximate != null && approximate.confidence() >= MIN_GAME_CONFIDENCE) {
                    referenceGame = approximate.reference();
                    warnings.add("미등록 게임 '" + gameName + "'은 Gemini의 사양 추정으로 " + gameLabel(referenceGame)
                            + " 기준에 가깝게 분류했습니다. 실제 게임 성능 측정이나 공식 요구 사양 검증 결과는 아닙니다.");
                } else {
                    warnings.add("'" + gameName + "'의 요구 사양을 신뢰성 있게 분류하지 못해 일반 게이밍 기본 예산을 적용합니다.");
                }
            }
            if (!referenceGame.isBlank()) {
                try {
                    gameMinimum = minimumBudgetService.gameMinimumFor(referenceGame);
                    suggestedGameBudget = gameDefault(gameMinimum);
                } catch (IllegalStateException ex) {
                    log.warn("게임 {} 가이드가 DB에 없어 일반 게이밍 기본 예산 적용", referenceGame);
                    warnings.add("게임별 목표 예산 데이터가 없어 일반 게이밍 기본 예산을 사용합니다.");
                    referenceGame = "";
                }
            }
        }

        if (!hasBudget && "UNSPECIFIED".equals(intent) && !hasGame) {
            throw new IllegalArgumentException("예산이나 사용 목적을 입력해 주세요. 예: '200만원' 또는 '배그용 PC'.");
        }

        String usage;
        long budget;
        if (!hasBudget) {
            usage = isGameIntent ? "GAMING" : isExplicitEngineUsage ? intent : "GAMING";
            budget = hasGame && suggestedGameBudget > 0 ? suggestedGameBudget : defaultBudget(usage);
            if ("WORK".equals(intent) || "VIDEO_EDITING".equals(intent)) {
                warnings.add("영상 편집·작업 전용 추천 엔진은 없어 기본 게이밍 예산 구간을 적용합니다. 작업 성능 최적화가 보장되지는 않습니다.");
            }
        } else {
            budget = statedBudget;
            // 게임명을 명시한 경우 예산을 우선한다. 단 게이밍 최소 예산 미만은 충돌 선택.
            if (hasGame) {
                if (budget < minimumBudgetService.minimumFor("GAMING")) {
                    return gameBudgetConflict(gameName, referenceGame, budget, suggestedGameBudget, warnings);
                }
                usage = usageByBudget(budget);
            } else if (isExplicitEngineUsage) {
                usage = intent;
                if (!withinRange(usage, budget)) {
                    return usageBudgetConflict(usage, budget, warnings);
                }
            } else {
                // VIDEO_EDITING/WORK는 전용 엔진이 없으므로 예산 구간을 기준으로 정규화
                usage = usageByBudget(budget);
                if ("WORK".equals(intent) || "VIDEO_EDITING".equals(intent)) {
                    warnings.add("요청하신 작업 용도는 유지되지만 전용 견적 엔진이 없어 예산 구간(" + usage + ")으로 추천합니다.");
                }
            }
        }

        if (hasGame && gameMinimum > 0 && budget < gameMinimum) {
            warnings.add(gameLabel(referenceGame) + "의 기존 상위 옵션 목표 예산 " + formatWon(gameMinimum)
                    + "보다 낮습니다. 상위 옵션 실행 성능을 보장하지 않습니다.");
        }
        // 기존 엔진의 용도별 허용 범위를 마지막으로 한 번 더 검증한다.
        minimumBudgetService.validateBudget(usage, budget);
        return new Decision("READY", new Plan(usage, budget, intent, gameName,
                referenceGame, List.copyOf(warnings)), "", List.of());
    }

    /** 충돌 시 프론트에서 명시적인 옵션을 선택하면 Gemini 재호출 없이 견적 생성한다. */
    public ResponseDTO.ResultList confirm(ConfirmRequest request) {
        if (request == null || request.budget() == null || request.budget() <= 0) {
            throw new IllegalArgumentException("확정할 예산을 입력해 주세요.");
        }
        if (request.usage() == null || request.usage().isBlank()) {
            throw new IllegalArgumentException("확정할 사용 용도를 입력해 주세요.");
        }
        String usage = BudgetRatioService.normalizeUsage(request.usage());
        minimumBudgetService.validateBudget(usage, request.budget());
        return estimateService.generateRecommendations(
                new RequestDTO.EstimateRequest(usage, request.budget(), List.of()));
    }

    public ResponseDTO.ResultList generate(Plan plan) {
        minimumBudgetService.validateBudget(plan.usage(), plan.budget());
        return estimateService.generateRecommendations(
                new RequestDTO.EstimateRequest(plan.usage(), plan.budget(), List.of()));
    }

    private Decision usageBudgetConflict(String desiredUsage, long desiredBudget, List<String> warnings) {
        MinimumBudgetService.BudgetRange row = rangeFor(desiredUsage);
        long useBudget = desiredBudget < row.minBudget()
                ? row.minBudget()
                : Math.min(row.maxBudget() == null ? desiredBudget : row.maxBudget(),
                           preferredMax(desiredUsage, row));
        List<Option> choices = new ArrayList<>();
        choices.add(new Option("KEEP_USAGE", "용도 유지 · " + formatWon(useBudget),
                desiredUsage + " 용도에 맞춰 예산을 변경합니다.", desiredUsage, useBudget));
        String actualBudgetUsage = findUsage(desiredBudget);
        if (actualBudgetUsage != null) {
            choices.add(new Option("KEEP_BUDGET", "예산 유지 · " + formatWon(desiredBudget),
                    "예산 구간(" + actualBudgetUsage + ")으로 추천합니다. 원래 용도에 특화된 구성은 아닐 수 있습니다.",
                    actualBudgetUsage, desiredBudget));
        }
        return new Decision("CONFIRMATION_REQUIRED", null,
                "입력 예산 " + formatWon(desiredBudget) + "은 " + desiredUsage
                        + " 허용 범위를 벗어납니다. 적용할 조건을 선택해 주세요.", List.copyOf(choices));
    }

    private Decision gameBudgetConflict(String gameName, String referenceGame, long budget,
                                        long suggestedGameBudget, List<String> warnings) {
        long lower = minimumBudgetService.minimumFor("GAMING");
        long adjusted = Math.max(lower, suggestedGameBudget);
        if (!withinRange("GAMING", adjusted)) adjusted = lower;
        List<Option> choices = new ArrayList<>();
        choices.add(new Option("KEEP_USAGE", "게임 용도 유지 · " + formatWon(adjusted),
                "게임용 견적을 만들 수 있는 예산으로 조정합니다. 게임 상위 옵션을 보장하지는 않습니다.",
                "GAMING", adjusted));
        String actual = findUsage(budget);
        if (actual != null) {
            choices.add(new Option("KEEP_BUDGET", "예산 유지 · " + formatWon(budget),
                    "예산 구간(" + actual + ")으로 견적을 생성합니다. '" + gameName
                            + "'의 게임 성능을 보장하지 않습니다.", actual, budget));
        }
        return new Decision("CONFIRMATION_REQUIRED", null,
                "입력하신 " + formatWon(budget) + "은 게임용 최소 예산 " + formatWon(lower)
                        + "보다 낮습니다. 어떤 조건으로 진행할까요?", List.copyOf(choices));
    }

    private CachedGame classifyCached(String gameName) {
        String key = gameName.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        CachedGame cached = gameCache.get(key);
        if (cached != null && cached.validUntil().isAfter(Instant.now())) return cached;
        try {
            GameClassification result = openAiService.classifyUnknownGame(gameName);
            if (result == null || result.getConfidence() == null
                    || !Double.isFinite(result.getConfidence()) || result.getConfidence() < 0
                    || result.getConfidence() > 1) return null;
            String ref = result.getReferenceGame() == null ? "" : result.getReferenceGame().trim().toUpperCase(Locale.ROOT);
            if (!REFERENCE_GAMES.contains(ref)) return null;
            CachedGame resolved = new CachedGame(ref, result.getConfidence(),
                    result.getReason() == null ? "" : result.getReason(),
                    Instant.now().plusSeconds(CACHE_TTL_SECONDS));
            if (gameCache.size() >= CACHE_LIMIT) gameCache.clear();
            gameCache.put(key, resolved);
            return resolved;
        } catch (ExternalAiTimeoutException | ExternalAiUnavailableException ex) {
            log.warn("미등록 게임 분류 서비스 응답 실패: {}", ex.getMessage());
            return null;
        }
    }

    private long gameDefault(long min) {
        if (min <= 0 || min > Long.MAX_VALUE / 2) throw new IllegalArgumentException("게임 가이드 예산이 올바르지 않습니다.");
        long proposed = (long) Math.ceil(min * GAME_BUDGET_BUFFER);
        long rounded = ((proposed + BUDGET_STEP - 1) / BUDGET_STEP) * BUDGET_STEP;
        return Math.max(minimumBudgetService.minimumFor("GAMING"), rounded);
    }

    private String recognizedGame(String value) {
        String normal = value.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return switch (normal) {
            case "LOL", "LEAGUEOFLEGENDS", "리그오브레전드", "롤" -> "LOL";
            case "PUBG", "BATTLEGROUNDS", "PLAYERUNKNOWNSBATTLEGROUNDS", "배틀그라운드", "배그" -> "PUBG";
            case "CYBERPUNK", "CYBERPUNK2077", "사이버펑크", "사이버펑크2077" -> "CYBERPUNK";
            case "CRIMSONDESERT", "붉은사막" -> "CRIMSON_DESERT";
            default -> "";
        };
    }

    private String gameLabel(String key) {
        return switch (key) {
            case "LOL" -> "리그 오브 레전드";
            case "PUBG" -> "배틀그라운드";
            case "CYBERPUNK" -> "사이버펑크 2077";
            case "CRIMSON_DESERT" -> "붉은사막";
            default -> "게임";
        };
    }

    private long defaultBudget(String usage) {
        MinimumBudgetService.BudgetRange range = rangeFor(usage);
        long proposed;
        if ("HIGH_END".equals(usage)) {
            proposed = Math.max(range.minBudget(), HIGH_END_DEFAULT);
        } else {
            long cap = preferredMax(usage, range);
            proposed = Math.round(((range.minBudget() + cap) / 2.0) / BUDGET_STEP) * BUDGET_STEP;
        }
        return range.maxBudget() == null ? proposed : Math.min(proposed, range.maxBudget());
    }

    private long preferredMax(String usage, MinimumBudgetService.BudgetRange range) {
        long preferred = switch (usage) {
            case "OFFICE" -> OFFICE_DISPLAY_MAX;
            case "GAMING" -> GAMING_DISPLAY_MAX;
            default -> range.minBudget();
        };
        return Math.max(range.minBudget(), range.maxBudget() == null ? preferred : Math.min(preferred, range.maxBudget()));
    }

    private String usageByBudget(long budget) {
        String result = findUsage(budget);
        if (result == null) {
            throw new IllegalArgumentException("입력 예산은 최소 " + formatWon(minimumBudgetService.minimumFor("OFFICE"))
                    + " 이상이어야 합니다.");
        }
        return result;
    }

    private String findUsage(long budget) {
        for (MinimumBudgetService.BudgetRange range : minimumBudgetService.currentRanges()) {
            if (budget >= range.minBudget() && (range.maxBudget() == null || budget <= range.maxBudget())) {
                return range.usage();
            }
        }
        return null;
    }

    private boolean withinRange(String usage, long budget) {
        MinimumBudgetService.BudgetRange r = rangeFor(usage);
        return budget >= r.minBudget() && (r.maxBudget() == null || budget <= r.maxBudget());
    }

    private MinimumBudgetService.BudgetRange rangeFor(String usage) {
        return minimumBudgetService.currentRanges().stream()
                .filter(r -> r.usage().equals(usage)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 사용 용도: " + usage));
    }

    private String formatWon(long value) {
        return String.format(Locale.KOREA, "%,d원", value);
    }
}
