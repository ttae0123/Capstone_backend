package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.AiAnalysisRequest;
import com.example.capstone_backend.dto.AiAnalysisResponse;
import com.example.capstone_backend.dto.ParsedRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpenAiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-3.5-flash-lite}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AiAnalysisResponse analyzeParts(AiAnalysisRequest request) {
        if (request == null || request.getParts() == null || request.getParts().isEmpty()) {
            throw new IllegalArgumentException("분석할 부품 정보가 없습니다.");
        }

        String partsList = request.getParts().stream()
                .map(p -> p.getCategory() + ": " + p.getName())
                .collect(Collectors.joining(", "));

        String prompt = String.format(
                "너는 하이엔드 PC 하드웨어 전문가야. 다음 부품 조합을 사용자 친화적으로 분석해줘.\n" +
                        "부품 리스트: [%s]\n\n" +
                        "가이드라인:\n" +
                        "1. 'pros'(장점)에는 부품 간의 성능 시너지, 선택한 용도에서의 강점, 가성비를 구체적으로 2개 적어줘.\n" +
                        "2. 'cons'(아쉬운 점)에는 발생 가능한 병목 현상, 부품 급 차이로 인한 불균형을 구체적으로 2개 적어줘.\n" +
                        "3. 모든 문장은 반드시 20자 이상의 한국어 완성형 문장으로 작성해.\n" +
                        "4. 반드시 JSON 스키마에 맞는 결과만 반환해.",
                partsList
        );

        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "pros", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "cons", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of("pros", "cons")
        );

        return callGeminiApi(prompt, schema, AiAnalysisResponse.class);
    }

    public ParsedRequest parseUserInput(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("자연어 견적 요청 내용을 입력해주세요.");
        }

        String prompt = String.format(
                "너는 사용자의 요구사항에서 PC 견적 조건을 추출하는 분석기야.\n" +
                        "사용자 입력: \"%s\"\n\n" +
                        "가이드라인:\n" +
                        "1. 'usage'는 반드시 [GAMING, VIDEO_EDITING, OFFICE] 중 하나만 반환해.\n" +
                        "2. 게임과 고사양 3D 작업은 GAMING으로 분류해.\n" +
                        "3. 영상 편집과 콘텐츠 제작은 VIDEO_EDITING으로 분류해.\n" +
                        "4. 문서 작업, 인터넷, 일반 사무, 가벼운 개발은 OFFICE로 분류해.\n" +
                        "5. 'budget'은 사용자가 언급한 금액을 원 단위 정수로 변환해. 예: 150만원 -> 1500000\n" +
                        "6. 예산을 명시하지 않았다면 임의 생성하지 말고 0을 반환해.\n" +
                        "7. 반드시 JSON 스키마에 맞는 결과만 반환해.",
                userInput.trim()
        );

        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "budget", Map.of("type", "INTEGER"),
                        "usage", Map.of("type", "STRING")
                ),
                "required", List.of("budget", "usage")
        );

        ParsedRequest result = callGeminiApi(prompt, schema, ParsedRequest.class);
        validateParsedRequest(result);
        return result;
    }

    private void validateParsedRequest(ParsedRequest result) {
        if (result == null) {
            throw new IllegalStateException("자연어 견적 조건을 분석하지 못했습니다.");
        }

        if (result.getUsage() == null || result.getUsage().isBlank()) {
            throw new IllegalArgumentException("PC 사용 용도를 확인하지 못했습니다.");
        }

        String normalizedUsage = result.getUsage().trim().toUpperCase();
        result.setUsage(normalizedUsage);

        if (!List.of("GAMING", "VIDEO_EDITING", "OFFICE").contains(normalizedUsage)) {
            throw new IllegalArgumentException("지원하지 않는 사용 용도입니다: " + normalizedUsage);
        }

        if (result.getBudget() == null || result.getBudget() <= 0) {
            throw new IllegalArgumentException(
                    "예산을 확인하지 못했습니다. 예: '게임용으로 300만원 견적 짜줘'처럼 예산을 함께 입력해주세요."
            );
        }

    }

    private <T> T callGeminiApi(String prompt, Map<String, Object> schema, Class<T> responseType) {
        validateApiKey();

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent";

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", prompt))
                        )
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            System.out.println("=== Gemini API 요청 전송 ===");
            System.out.println("model = " + model);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            System.out.println("=== Gemini API 응답 수신 성공 ===");

            String responseBody = response.getBody();

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("Gemini API 응답 본문이 비어 있습니다.");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");

            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new IllegalStateException("Gemini API 응답에 candidates가 없습니다.");
            }

            JsonNode parts = candidates.get(0).path("content").path("parts");

            if (!parts.isArray() || parts.isEmpty()) {
                throw new IllegalStateException("Gemini API 응답에 content.parts가 없습니다.");
            }

            String rawText = parts.get(0).path("text").asText();

            if (rawText == null || rawText.isBlank()) {
                throw new IllegalStateException("Gemini API가 빈 텍스트를 반환했습니다.");
            }

            String cleanJson = cleanJsonText(rawText);
            return objectMapper.readValue(cleanJson, responseType);

        } catch (HttpStatusCodeException e) {
            System.err.println("============= GEMINI API HTTP ERROR =============");
            System.err.println("HTTP 상태: " + e.getStatusCode());
            System.err.println("응답 내용: " + e.getResponseBodyAsString());
            System.err.println("=================================================");

            if (responseType.equals(AiAnalysisResponse.class)) {
                return responseType.cast(createAnalysisFallback());
            }

            throw new IllegalStateException(
                    "자연어 분석 서버 호출에 실패했습니다. Gemini API 키와 서버 설정을 확인해주세요."
            );

        } catch (Exception e) {
            System.err.println("============= GEMINI API ERROR =============");
            System.err.println("에러 유형: " + e.getClass().getName());
            System.err.println("에러 메시지: " + e.getMessage());
            System.err.println("============================================");

            if (responseType.equals(AiAnalysisResponse.class)) {
                return responseType.cast(createAnalysisFallback());
            }

            throw new IllegalStateException(
                    "자연어 견적 조건 분석 중 오류가 발생했습니다.",
                    e
            );
        }
    }

    private void validateApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Gemini API 키가 설정되어 있지 않습니다. GEMINI_API_KEY 환경변수를 확인해주세요."
            );
        }
    }

    private String cleanJsonText(String rawText) {
        String cleanJson = rawText.trim();

        if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson
                    .replaceFirst("^```json\\s*", "")
                    .replaceFirst("^```\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }

        return cleanJson;
    }

    private AiAnalysisResponse createAnalysisFallback() {
        AiAnalysisResponse fallback = new AiAnalysisResponse();

        fallback.setPros(List.of(
                "선택하신 부품은 기본적인 조립 규격과 주요 호환 조건을 기준으로 구성되어 있습니다.",
                "추천 로직에서 성능과 가격을 함께 고려한 부품 조합이므로 일반적인 사용 환경에 적합합니다."
        ));

        fallback.setCons(List.of(
                "현재 AI 상세 분석 서비스를 사용할 수 없어 세부적인 병목 및 성능 균형 평가는 제한됩니다.",
                "구매 전 실제 제품 페이지에서 전원 요구량과 케이스 장착 공간 등 최종 사양을 다시 확인하는 것이 좋습니다."
        ));

        return fallback;
    }
}
