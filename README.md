# PickPC 백엔드 — 용도별 동적 최소 예산

이 프로젝트는 기존 Java 21 / Spring Boot 4.0.5 프로젝트에 **용도별 최소 예산 자동 계산**을 통합한 버전입니다. `/api/estimates/recommend`, `/api/estimates/natural-language`, 기존 응답 DTO, 30초 추천 탐색 제한, DB 기반 부품 예산 비율 방식은 유지합니다.

## 주요 변경

- `GAMING` **2,500,000원** 최소 예산 상수를 일반 견적 및 자연어 분석에서 제거했습니다.
- 최소 예산은 비율 테이블의 `MIN(budget)`이 아니라, **현재 부품 DB에서 성능·용량 정책과 검증 가능한 호환성을 만족하는 최저가 조합의 실제 부품 가격 합계**로 산출합니다.
- 대상 용도: `OFFICE`, `GAMING`, `VIDEO_EDITING` (기존 `WORK` 별칭 지원).
- `recommendation_minimum_budgets`에 `usage_type`, `minimum_budget`, `updated_at` 저장. (`usage`는 MariaDB 12.2에서 DDL 문법 충돌 방지 차원에서 사용하지 않음.) JPA `spring.jpa.hibernate.ddl-auto=update`가 설정돼 있으면 시작 시 테이블을 생성합니다.
- 서버 시작 및 주기적인 최소 예산 자동 재계산을 실행하지 않습니다. `refreshAll()` 계산/저장 메서드는 나중에 DB 적재 파이프라인에서 명시적으로 호출할 수 있도록 유지합니다. 추천 API는 `recommendation_minimum_budgets`에 저장된 값만 조회합니다.
- 비율 기반 후보 탐색에서 결과를 찾지 못해도 최소 기준 조합이 요청 예산 안에 있다면 **최저가 조합 1개를 보완 반환**합니다.
- 사무용 저출력 200W 파워를 방지하기 위해 최소 파워 출력 정책 및 ATX 파워 필터를 적용했습니다. 메인보드 크기와 케이스 지원 크기도 검사합니다.
- 미달 예산 등 잘못된 입력은 HTTP 400과 JSON 메시지를 반환합니다.
- Gemini 모델 설정은 `gemini.api.model=${GEMINI_MODEL:gemini-3.5-flash-lite}`로 외부 환경변수에서 변경할 수 있습니다.

## 용도별 최소 성능 정책

정책은 `src/main/java/com/example/capstone_backend/service/MinimumBudgetCalculator.java`의 `Policy.of()`에 정의합니다. **이 값은 팀에서 조정할 수 있는 프로젝트 기본 정책이지, 모든 PC 사용자를 위한 객관적 최저 사양이 아닙니다.**

| 용도 | CPU PassMark | GPU PassMark | RAM | SSD | 최소 파워 | 외장 GPU |
|---|---:|---:|---|---|---:|---|
| OFFICE | 10,000 이상 | 해당 없음 | 16GB 이상, 1모듈 이상 | 500GB 이상 | 450W | 제외, 내장그래픽 가능 CPU 필요 |
| GAMING | 18,000 이상 | 19,000 이상 | 16GB 이상, 2모듈 이상 | 500GB 이상 / SSD 벤치 10,000 이상 | 550W 및 GPU 권장 출력 이상 | 필수 |
| VIDEO_EDITING | 26,000 이상 | 20,000 이상 | 32GB 이상, 2모듈 이상 | 1TB 이상 / SSD 벤치 20,000 이상 | 650W 및 GPU 권장 출력 이상 | 필수 |

사무용 SSD는 벤치마크 점수 하한을 두지 않습니다. 모든 용도에서 가격이 0원 이하인 부품을 제외합니다.

호환성은 **CPU-메인보드 소켓, CPU-쿨러 소켓, 메인보드-RAM 타입, 메인보드-케이스 규격, GPU-케이스 길이, 쿨러-케이스 높이, GPU 권장 파워**를 검사합니다. 고성능 CPU 또는 오버클럭 K 계열 CPU와 일부 보급형 메인보드의 조합을 제외합니다. 현재 DB에 케이스의 파워 폼팩터별 지원 정보가 없으므로 최소 예산 분석에서는 ATX 파워만 인정하지만 실제 모든 케이스에서 ATX 파워가 장착 가능하다는 뜻은 아닙니다.

**남은 데이터 한계:** CPU TDP/최대 전력, 쿨러 냉각 능력, 케이스의 PSU 지원 규격, SSD 인터페이스, CPU의 정형 내장그래픽 여부가 DB에 없습니다. 따라서 계산 금액은 위와 같이 **확인 가능한 규칙의 하한**이며, 물리적 조립과 안정성을 100% 보장하는 최소 예산은 아닙니다. 특히 쿨러 용량 검증은 향후 크롤러 컬럼 확장이 필요합니다.

## 검증 예시 (2026-10-12 제출된 `pc_db(5).sql` 기준)

실제 덤프의 8개 부품 테이블을 파싱해서 계산기를 실행한 결과입니다. 데이터나 정책이 바뀌면 금액이 변합니다.

| 용도 | 계산된 최소 예산 |
|---|---:|
| OFFICE | 435,100원 |
| GAMING | 942,500원 |
| VIDEO_EDITING | 1,384,100원 |

각각 해당 예산으로 자동 추천 결과가 반환되는지, 1원 낮은 예산을 거부하는지를 모의 Repository를 이용한 Java 실행 테스트로 확인했습니다. 변경된 메인 Java 소스도 기존 빌드 JAR의 의존성으로 **javac 컴파일 검증**을 통과했습니다. 이 검증은 실제 MariaDB 서버 접속이나 전체 Gradle 테스트 실행을 대체하지는 않습니다.

## 실행

1. IntelliJ의 실행 구성(Environment variables)에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `GEMINI_API_KEY`를 설정합니다. 예: `DB_URL=jdbc:mariadb://localhost:3306/pc_db`. 필요한 경우 `GEMINI_MODEL`을 함께 설정합니다.
2. Java 21에서 Windows 명령 프롬프트를 열고 아래를 실행합니다.

```bat
gradlew.bat test
gradlew.bat bootRun
```

3. 서버 시작 시 최소 예산은 재계산하지 않습니다. **처음 한 번만** 아래 명령으로 부품 DB에서 최소 예산을 계산하고 DB에 저장합니다.

```bat
gradlew.bat initializeMinimumBudgets
```

이후에는 다음 SQL로 각 용도의 행이 저장됐는지 확인합니다.

최소 예산 API:

```http
GET http://localhost:8080/api/estimates/minimum-budgets
```

```sql
SELECT usage_type, minimum_budget, updated_at
FROM recommendation_minimum_budgets
ORDER BY usage_type;
```

자동 견적 호출:

```http
POST http://localhost:8080/api/estimates/recommend
Content-Type: application/json

{"usage":"GAMING","budget":1000000,"brands":[]}
```

자연어 입력은 기존 API를 그대로 사용하며 요청 키는 `userInput`입니다.

```json
{"userInput":"150만원으로 게임용 컴퓨터 맞춰줘"}
```

### 최소 예산 갱신 정책 (자동 갱신 제거)

- Spring Boot 실행 시 `refreshAll()`은 호출되지 않습니다. `@Scheduled`, `@EventListener(ApplicationReadyEvent.class)`, `@EnableScheduling`을 제거했습니다.
- 추천 API는 `recommendation_minimum_budgets`에 저장된 `minimum_budget`을 읽습니다. 테이블이 없거나 값이 없다면 해당 API는 정상 동작하지 않습니다.
- **최초 적재가 필요할 경우**, `gradlew.bat initializeMinimumBudgets`를 1회 수동 실행합니다. 이 명령은 별도 프로세스로 `MinimumBudgetService.refreshAll()`을 호출하고 종료합니다. `spring.jpa.hibernate.ddl-auto=update`는 빈 테이블만 만들 수 있고 최소 예산 행은 생성하지 않습니다.
- 부품 크롤링과 DB 적재로 데이터가 갱신되어도 당분간 기존 최소 예산이 유지됩니다. 그 결과 새 부품 가격에 비해 최소 예산이 오래된 값일 수 있으므로, 자동 적재 연동 시 데이터 갱신 **완료 후** 재계산이 실행되도록 순서를 정하세요.

다음 SQL 결과가 `OFFICE`, `GAMING`, `VIDEO_EDITING` 3행인지 확인하세요.

```sql
SELECT usage_type, minimum_budget, updated_at
FROM recommendation_minimum_budgets
ORDER BY usage_type;
```

### 테스트

```bat
gradlew.bat test --tests "*MinimumBudgetCalculatorTest"
gradlew.bat test --tests "*BudgetRatioServiceTest"
```

실제 로컬 MariaDB를 연결한 뒤 최저 예산, 최저 예산 - 1원(HTTP 400), 일반적인 예산(100만/200만/300만), 그리고 `minimum_budget` 조회 API를 확인하세요. 이 버전은 크롤러 DB 갱신 시 자동 재계산하지 않습니다.

기존 예산 비율 테이블의 18개 데이터는 **변경하지 않으며**, 최저 예산 계산과는 독립적으로 목표 부품 예산을 산정합니다.

### MariaDB 12.2 `usage` DDL 오류 수정

이전 버전에서 발생한 `Error 1064 ... near usage varchar(30)`를 해결하기 위해 최소 예산 엔티티 컬럼명을 `usage_type`으로 명시했습니다. Java 필드명과 HTTP JSON 응답 필드명 `usage`는 기존대로 유지합니다. 기존의 예산 비율 테이블(`recommendation_budget_ratios`)에는 영향이 없습니다.

오류가 발생한 시점에는 테이블이 생성되지 않았으므로 기존 테이블 삭제가 필요하지 않습니다. 새 버전에서 `ddl-auto=update`가 테이블을 생성하면 다음을 확인하세요.

```sql
SHOW TABLES LIKE 'recommendation_minimum_budgets';
SELECT usage_type, minimum_budget, updated_at
FROM recommendation_minimum_budgets ORDER BY usage_type;
```

`initializeMinimumBudgets`는 수동으로 실행할 때만 작동하며 일반 `bootRun` 또는 서버 재시작 시 자동 실행되지 않습니다. 미래에는 부품 DB 적재 완료 후 이 명령을 호출하거나 `MinimumBudgetService.refreshAll()`을 파이프라인에 연결하면 됩니다.
