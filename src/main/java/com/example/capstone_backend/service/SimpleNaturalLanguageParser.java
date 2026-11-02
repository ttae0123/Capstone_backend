package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.ParsedRequest;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 예산/용도/등록 게임을 간단한 규칙으로 추출한다.
 * 이하/이상/부정/비교 같은 부가 표현은 조건 판단에서 무시한다.
 * 등록되지 않은 게임명처럼 해석할 수 없는 핵심 정보는 기존 Gemini 분석으로 보낸다.
 * 예산 검증과 충돌 처리는 기존 NaturalLanguageEstimateService가 담당한다.
 */
public final class SimpleNaturalLanguageParser {
    private SimpleNaturalLanguageParser() { }

    // 만원, 만 원, 만, 원 형태. 소수/한글 수/억 단위는 안전하게 Gemini에 위임한다.
    private static final Pattern BUDGET = Pattern.compile(
            "(?<![0-9.,])([0-9]+(?:,[0-9]{3})*)(?:\\s*)(만원|만\\s*원|만|원)(?![0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REGISTERED_GAMES = Pattern.compile(
            "리그\\s*오브\\s*레전드|league\\s*of\\s*legends|배틀그라운드|playerunknown'?s\\s*battlegrounds|"
            + "사이버펑크\\s*2077|cyberpunk\\s*2077|crimson[ _-]*desert|붉은사막|"
            + "사이버펑크|cyberpunk|battlegrounds|pubg|롤|배그|lol",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern USAGES = Pattern.compile(
            "하이엔드용|하이엔드|게이밍용|게이밍|게임용|사무용|사무",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // 추출 대상이 아닌 자연어 수식어는 의미 검사 없이 버린다.
    // 남은 표현에서 알 수 없는 게임명/작업 용도가 나오면 기존 Gemini가 해석한다.
    private static final List<String> SAFE_PHRASES = List.of(
            "추천해주시겠어요", "추천해주실래요", "견적을", "견적도", "견적서", "컴퓨터", "피씨",
            "맞추고싶어요", "맞추고싶어", "맞춰주세요", "맞춰줘요", "맞춰줘", "맞추고", "맞추려는데",
            "추천해주세요", "추천해줘요", "추천해줘", "부탁드립니다", "부탁해요", "부탁해", "추천", "맞춰",
            "견적", "여유", "예산", "금액", "본체", "데스크톱", "데스크탑", "돌아가는", "돌릴", "돌아갈",
            "가능한", "하고싶어", "하고싶어요", "하고", "싶어요", "싶어", "짜주세요", "짜줘", "짜줘요",
            "제작해주세요", "만들어줘", "살거야", "살래", "구매할래", "구매하려고", "부탁", "으로", "짜리", "정확히",
            "이하여도", "이상이어도", "이하면", "이상이면", "미만으로", "초과해서",
            "이하로", "이상으로", "미만", "초과", "이하", "이상", "최대", "최소", "정도", "쯤", "약", "대략",
            "아니고", "아니면", "아니라", "안하고", "안해도", "안해", "안할", "안하는", "안쓰고", "안써", "안",
            "않아도", "않고", "않는", "않아", "않을", "없이", "빼고", "제외하고", "제외", "말고", "말고는",
            "보다", "저렴하게", "저렴한", "저렴", "싸게", "싼", "비싸게", "비싼", "더", "적게", "많게",
            "혹은", "또는", "같이", "겸", "못해도", "적어도", "넘게", "아래", "위로", "내로", "이내",
            "pc", "PC", "요", "좀", "한대", "한 대", "대", "을", "를", "에", "가", "이", "은", "는", "로", "도", "용"
    );

    /** null이면 규칙으로 확정하지 못했다는 뜻이며 Gemini 처리가 필요하다. */
    public static ParsedRequest tryParse(String userInput) {
        if (userInput == null || userInput.isBlank() || userInput.length() > 1500) return null;
        String text = Normalizer.normalize(userInput.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        // 수식어/부정어를 이유로 Gemini로 넘기지 않는다.
        // 다만 복수 금액·복수 게임·서로 다른 용도는 기존처럼 AI 해석에 맡긴다.

        Long budget = null;
        Matcher budgetMatcher = BUDGET.matcher(text);
        StringBuffer withoutBudget = new StringBuffer();
        while (budgetMatcher.find()) {
            if (budget != null) return null;  // 복수 금액의 의미는 규칙으로 확정하지 않는다.
            try {
                BigInteger number = new BigInteger(budgetMatcher.group(1).replace(",", ""));
                BigInteger multiplier = budgetMatcher.group(2).startsWith("만")
                        ? BigInteger.valueOf(10_000L) : BigInteger.ONE;
                budget = number.multiply(multiplier).longValueExact();
            } catch (RuntimeException ex) {
                return null;
            }
            budgetMatcher.appendReplacement(withoutBudget, " ");
        }
        budgetMatcher.appendTail(withoutBudget);

        String remaining = withoutBudget.toString();
        Matcher gameMatcher = REGISTERED_GAMES.matcher(remaining);
        StringBuffer withoutGames = new StringBuffer();
        String game = null;
        while (gameMatcher.find()) {
            String current = canonicalGame(gameMatcher.group());
            if (game != null) return null; // 여러 게임의 우선순위는 Gemini에 위임
            game = current;
            gameMatcher.appendReplacement(withoutGames, " ");
        }
        gameMatcher.appendTail(withoutGames);

        remaining = withoutGames.toString();
        Matcher usageMatcher = USAGES.matcher(remaining);
        StringBuffer withoutUsages = new StringBuffer();
        String usage = null;
        while (usageMatcher.find()) {
            String current = canonicalUsage(usageMatcher.group());
            if (usage != null) return null; // 여러 용도가 명시되면 Gemini에 위임
            usage = current;
            usageMatcher.appendReplacement(withoutUsages, " ");
        }
        usageMatcher.appendTail(withoutUsages);

        if (game != null && usage != null && !"GAMING".equals(usage)) return null;
        remaining = withoutUsages.toString();
        // 예산/용도/게임 외의 흔한 표현(부정·상하한 포함)은 무시한다.
        // 미등록 게임 등 인식되지 않은 핵심 단어가 남을 때만 Gemini로 전달한다.
        // 띄어쓰기 제거 후 처리하므로 '추천해 줘' 같은 표기도 처리한다.
        String residue = remaining.replaceAll("[\\s\\p{Punct}·]+", "");
        List<String> sorted = new ArrayList<>(SAFE_PHRASES);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String phrase : sorted) {
            residue = residue.replace(phrase.toLowerCase(Locale.ROOT).replace(" ", ""), "");
        }
        if (!residue.isEmpty()) return null;
        if (budget == null && usage == null && game == null) return null;

        ParsedRequest result = new ParsedRequest();
        result.setBudget(budget == null ? 0L : budget);
        result.setUsage(game != null ? "GAMING" : usage == null ? "UNSPECIFIED" : usage);
        result.setGameName(game == null ? "" : game);
        return result;
    }

    private static String canonicalGame(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (key.equals("롤") || key.equals("lol") || key.equals("리그오브레전드")
                || key.equals("leagueoflegends")) return "LOL";
        if (key.equals("배그") || key.equals("배틀그라운드") || key.equals("pubg")
                || key.contains("battlegrounds")) return "PUBG";
        if (key.startsWith("사이버펑크") || key.startsWith("cyberpunk")) return "CYBERPUNK";
        return "CRIMSON_DESERT";
    }

    private static String canonicalUsage(String value) {
        if (value.startsWith("하이엔드")) return "HIGH_END";
        if (value.startsWith("게이밍") || value.startsWith("게임")) return "GAMING";
        return "OFFICE";
    }
}
