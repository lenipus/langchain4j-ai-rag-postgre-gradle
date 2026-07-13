# Gradle 환경 설정

이 프로젝트는 Maven에서 Gradle로 전환하면서 Maven 관련 파일(`pom.xml`, `pom_boot.xml`, `pom_tomcat.xml`, `.vscode/settings.json`의 maven 설정 등)을 제거하고 Gradle만 사용하는 구조로 정리했다. 이 문서는 그 과정에서 정리된 Gradle 관련 개념과 설정 방법을 기록한다.

## 1. Gradle Wrapper란

`gradlew`/`gradlew.bat`는 프로젝트에 고정된 특정 Gradle 버전을 자동으로 내려받아 실행해주는 런처 스크립트다. 아래 4개 파일이 한 세트로 동작한다.

```
gradle/wrapper/gradle-wrapper.jar          # wrapper 부트스트랩 실행 코드
gradle/wrapper/gradle-wrapper.properties   # 사용할 Gradle 버전(distributionUrl) 설정
gradlew                                    # Mac/Linux용 실행 스크립트
gradlew.bat                                # Windows용 실행 스크립트
```

`gradlew`를 실행하면 `gradle-wrapper.properties`의 `distributionUrl`을 읽고, 필요하면 해당 버전을 내려받아 `GRADLE_USER_HOME/wrapper/dists`에 풀어 쓴다. 이 방식 덕분에 PC마다 Gradle을 따로 설치할 필요가 없고, 팀원/CI 서버 모두 항상 동일한 버전으로 빌드된다.

이 프로젝트는 PC에 Gradle을 별도로 설치해두지 않고(`D:\PROJ_KOSII\gradle`에 있던 gradle-8.14.5/9.6.1 배포판은 삭제함), 전적으로 wrapper 방식만 사용한다.

## 2. Wrapper 생성/재생성 절차 (로컬에 Gradle이 전혀 없을 때)

wrapper 파일도 결국 `gradle wrapper` 태스크를 실행해서 만드는 것이라, 이 작업을 하려면 어딘가에 Gradle 실행 파일이 한 번은 있어야 한다. 영구 설치 없이 임시로만 사용하고 지운다.

1. Gradle 배포본을 임시 폴더에 내려받아 압축 해제
   - `https://services.gradle.org/distributions/gradle-<version>-bin.zip`
   - 예: `C:\temp\gradle-9.6.1-tmp\gradle-9.6.1`
2. 터미널에서 `JAVA_HOME`을 임시 지정 (Gradle 자체를 구동할 JVM)

   ```powershell
   $env:JAVA_HOME = "D:\PROJ_KOSII\java\jdk-21.0.11+10"
   ```

3. 프로젝트 루트로 이동 후 wrapper 생성

   ```powershell
   cd D:\PROJ_KOSII\workspace.ai.gradle\langchain4j-ai-rag-postgre
   C:\temp\gradle-9.6.1-tmp\gradle-9.6.1\bin\gradle.bat wrapper --gradle-version 9.6.1
   ```

4. 임시로 받은 Gradle 폴더 삭제 (더 이상 필요 없음)
5. 이후부터는 `.\gradlew.bat`만 사용

### JDK 관련 주의사항

Gradle 자체가 Java 프로그램이므로, wrapper를 생성하거나 `gradlew`를 실행할 때도 `JAVA_HOME`(또는 PATH의 `java`)이 반드시 있어야 한다. 이때 두 가지 JDK 개념을 구분해야 한다.

| 구분 | 용도 | 설정 위치 |
|---|---|---|
| Gradle 구동용 JVM | Gradle 프로세스 자체를 띄움 | 실행 시 `JAVA_HOME` 환경변수 |
| 프로젝트 컴파일용 JDK | 소스 컴파일 (toolchain) | `build.gradle`의 `java.toolchain.languageVersion` (현재 21) |

두 JDK는 버전이 달라도 무방하다. Gradle의 Java Toolchain 기능이 컴파일용 JDK는 별도로 찾아서 사용한다.

## 3. 리파지터리(의존성 캐시) 경로 변경 — 시도했으나 보류

기본적으로 Gradle은 의존성 캐시와 wrapper가 내려받은 배포본을 `GRADLE_USER_HOME`(기본값 `~/.gradle`)에 저장한다.

처음엔 프로젝트 루트 `gradle.properties`에 `systemProp.gradle.user.home=D:/PROJ_KOSII/gradle/repository`를 넣어서 이 프로젝트에서만 다른 경로를 쓰도록 시도했으나, **실제로는 동작하지 않는 것으로 확인됨** (`./gradlew build` 실행 후 캐시가 여전히 기본 `~/.gradle`에 쌓임, `D:\PROJ_KOSII\gradle\repository`는 비어있음).

원인: Gradle은 `GRADLE_USER_HOME` 위치 자체를 먼저 확정한 뒤에야 그 프로젝트의 `gradle.properties`를 읽어서 `systemProp.*` 값들을 적용한다. 즉 `GRADLE_USER_HOME`을 결정하는 시점이 이 설정을 읽는 시점보다 이르기 때문에, 이 방법으로는 자기 자신의 위치를 바꿀 수 없다.

**실제로 동작하는 대안** (필요해지면 사용):

| 방법 | 특징 |
|---|---|
| `GRADLE_USER_HOME` 환경변수 | 확실히 동작하지만 시스템 전역에 적용됨 |
| `gradlew.bat -g D:\PROJ_KOSII\gradle\repository build` | 확실히 동작, 매번 플래그 필요 |
| 프로젝트 전용 래퍼 스크립트 (`gradlew-local.bat` 등, 내부에서 `set GRADLE_USER_HOME=...` 후 `gradlew.bat` 위임) | 확실히 동작, 전역 환경변수 안 건드리고 플래그도 안 쳐도 됨 |

이 프로젝트는 당장은 번거로움 대비 실익이 적어 **기본 경로(`~/.gradle`) 그대로 사용하기로 결정**했다. 나중에 필요해지면 위 표의 래퍼 스크립트 방식을 적용하면 된다.

## 4. 폐쇄망(넥서스만 접속 가능) 환경 대응

개발 PC(인터넷 가능)와 폐쇄망(넥서스만 접속 가능, 공공 프로젝트 배포 환경 등)의 차이는 `gradle-wrapper.properties`의 `distributionUrl` 한 줄뿐이다. 나머지 항목은 동일하다.

### 4-1. 넥서스에 저장소 생성 (Nexus 2 기준)

이 프로젝트가 쓰는 넥서스는 Nexus Repository Manager **2.x**(URL 패턴이 `content/groups/...`인 구버전)다. Nexus 2에는 Nexus 3의 "raw" 포맷이 없어서, **Maven2 hosted 저장소에 임의 파일을 업로드하는 방식**으로 대신한다. Gradle wrapper는 GAV(그룹/아티팩트/버전) 구조를 전혀 신경 쓰지 않고 그 URL에 단순 HTTP GET만 하므로 문제없이 동작한다.

관리자 콘솔 → Repositories → **Add → Hosted Repository**에서 아래처럼 생성한다.

| 필드 | 값 |
|---|---|
| Repository ID | `raw-hosted` |
| Repository Name | `raw-hosted` |
| Repository Type | `hosted` |
| Provider | `Maven2` |
| Format | `maven2` (자동) |
| Repository Policy | `Release` |
| Deployment Policy | `Disable Redeploy` (같은 경로에 재업로드하려면 나중에 `Allow Redeploy`로 바꾸거나 기존 파일을 먼저 삭제해야 함) |
| Publish URL | `True` (필수 — `False`면 외부에서 URL 접근 자체가 안 됨) |

### 4-2. Gradle 배포본 업로드

`raw-hosted` 저장소의 **Artifact Upload** 탭에서 업로드한다.

1. **GAV Definition**: `GAV Parameters` 선택
2. GAV 값 입력
   - Group Id: `gradle`
   - Artifact Id: `gradle-dist`
   - Version: `9.6.1`
   - Packaging: `zip`
3. **Select Artifact(s) to Upload...** → `https://services.gradle.org/distributions/gradle-9.6.1-bin.zip`에서 받은 원본 zip 선택 (재압축하지 않음 — 배포본 내부 구조가 달라질 수 있음)
4. **Extension** 필드에 `zip` 입력 (비워두면 기본값 `jar`로 잘못 들어감)
5. **Add Artifact** → **Upload Artifact(s)**

업로드되면 `Browse Index` 탭에서 아래처럼 GAV 구조로 경로가 생성된 걸 확인할 수 있다.

```
raw-hosted / gradle / gradle-dist / 9.6.1 / gradle-dist-9.6.1.zip
```

최종 다운로드 URL (검증 완료, 브라우저에서 정상 다운로드됨):

```
http://kosiidvlp.iptime.org:11081/nexus/content/repositories/raw-hosted/gradle/gradle-dist/9.6.1/gradle-dist-9.6.1.zip
```

### 4-3. gradle-wrapper.properties 작성

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
networkTimeout=10000
retries=0
retryBackOffMs=500
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists

# 개발 PC(인터넷 접속 가능) 환경 - 기본 사용
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip

# 폐쇄망(사내 넥서스만 접속 가능) 환경 - 위 distributionUrl 줄을 주석 처리하고 아래 줄의 주석을 해제해서 사용
#distributionUrl=http\://kosiidvlp.iptime.org:11081/nexus/content/repositories/raw-hosted/gradle/gradle-dist/9.6.1/gradle-dist-9.6.1.zip
```

`distributionUrl`은 활성화된(주석 아닌) 줄이 하나여야 한다 (같은 키가 중복되면 프로퍼티 파서가 마지막 줄 값으로 덮어씀). 환경 전환 시 두 줄 중 하나만 주석 처리/해제하는 방식으로 수동 스위치한다.

넥서스가 `services.gradle.org`를 프록시하는 저장소를 이미 구성해뒀다면, 수동 업로드 없이 그 프록시 URL로 바로 받을 수도 있다.

## 5. 참고 — 별도 설치한 Gradle 배포본이 필요 없는 이유

Maven은 전통적으로 PC에 Maven을 한 번 설치해두고 모든 프로젝트가 공유하는 모델이지만, Gradle은 프로젝트마다 버전이 다를 수 있다는 전제 하에 wrapper가 기본이다. `gradlew`로 빌드하는 한 시스템에 Gradle을 미리 설치해둘 필요가 없고, wrapper가 내려받는 사본이 곧 실제 사용되는 사본이다. 별도 설치가 의미 있는 유일한 경우는 wrapper 자체를 새로 만들거나 버전을 바꿀 때 부트스트랩 도구로 쓸 때뿐이며, 이마저도 2절처럼 임시로만 받아서 쓰면 된다.

## 6. JDK toolchain 경로 명시 (`gradle.properties`)

`build.gradle`은 `java.toolchain.languageVersion = 21`을 요구하는데, 이 JDK가 `D:\PROJ_KOSII\java\jdk-21.0.11+10`처럼 비표준 경로에 있으면 Gradle의 자동 탐색(IntelliJ `.jdks`, sdkman, Windows `Program Files\Java` 등 정해진 위치만 훑음)이 못 찾을 수 있다. 이 프로젝트엔 자동 다운로드 안전장치(`foojay-resolver-convention` 플러그인)도 없어서, 못 찾으면 "No matching toolchain found" 에러로 빌드가 실패한다.

그래서 프로젝트 루트 `gradle.properties`에 명시적으로 경로를 등록해둔다.

```properties
org.gradle.java.installations.paths=D:/PROJ_KOSII/java/jdk-21.0.11+10
```

- `JAVA_HOME`(Gradle 구동용 JVM)과는 별개다 — 이건 toolchain(컴파일용) JDK를 찾는 위치를 알려주는 것.
- 프로젝트가 21만 요구하므로 21 경로 하나만 등록. 다른 버전(17 등)은 넣을 필요 없음.
- `.vscode/settings.json`의 `java.configuration.runtimes`와는 완전히 별개의 파일/도구다 (전자는 Gradle CLI용, 후자는 VSCode Java Language Server용). 한쪽을 고쳐도 다른 쪽엔 영향 없다.

## 7. `bin/` vs `build/` — 두 개의 독립된 컴파일 결과물

이 프로젝트를 VSCode(또는 Eclipse)로 열면 `bin/`이라는 폴더가 추가로 생긴다. `build/`와 헷갈리기 쉬운데 완전히 다른, 서로 무관한 두 파이프라인이다.

| | `build/` | `bin/` |
|---|---|---|
| 만드는 주체 | **Gradle** (`gradlew build`, `bootJar` 등) | **JDT/Buildship** (VSCode Java 확장, 저장할 때마다 자동 컴파일) |
| Gradle이 인지? | 예, 이게 Gradle 자신의 산출물 | **아니요**, Gradle은 `bin/`의 존재 자체를 모름 |
| 담는 내용 | `libs/*.jar`, `test-results/`, `reports/`, `classes/` (컴파일 결과와 리소스가 분리된 폴더 구조) | `main/`, `test/` (컴파일된 클래스와 복사된 리소스가 한 폴더에 섞임, `.classpath`의 `output` 설정에 따름) |
| 왜 따로 만드는가 | — | Eclipse JDT가 실시간 자동완성/에러표시를 위해 쓰는 자체 증분 컴파일러(ECJ)가, Gradle의 실제 빌드 프로세스와 충돌 없이 동작하도록 폴더를 분리 (Eclipse의 아주 오래된 관례, `bin/`이 기본값) |
| Maven과의 차이 | Maven은 `target/classes`를 IDE(m2e)가 그대로 재사용해서 이런 중복이 안 생김. Gradle+Buildship 조합만 이렇게 별도 폴더를 만듦 | |

`.gitignore`에 `bin/`을 추가해뒀다 (`build/`처럼 원래 있었어야 하는데 빠져 있었음).

## 8. Boot Dashboard 실행 시 Mockito ClassNotFoundException — 해결됨

VSCode Spring Boot Dashboard로 앱을 Run/Debug하면 `.vscode/launch.json`의 `"type": "java"` 설정을 타고 **`bin/`을 classpath로 직접 실행**한다 (Gradle을 거치지 않음, `gradlew bootRun`을 대신 호출해주는 게 아님).

이 프로젝트에서 Boot Dashboard로 실행 시 아래 에러가 발생했었다.

```
Caused by: java.lang.ClassNotFoundException: org.mockito.Mockito
```

**방아쇠(trigger)**: 실행에 사용된 classpath에 `bin/main`뿐 아니라 `bin/test`까지 섞여 들어감.

**근본 원인**: `EgovHybridRetrieverToggleTest.java`의 내부 클래스 `TestBeans`가 `@Configuration`으로 선언되어 있었다. 원래는 `ApplicationContextRunner`로 테스트 안에서만 수동으로 등록해서 쓰려는 목(mock) 빈 모음인데, `@Configuration`은 일반 컴포넌트 스캔 대상이라 classpath에 노출되면(bin/test 혼입) 그대로 스캔에 걸린다. 그 결과 `embeddingStore()`(Mockito mock 반환)가 운영 설정(`EgovLangChain4jConfig`)의 진짜 `embeddingStore` bean을 덮어썼고, Mockito는 `testImplementation` 스코프라 실제 실행 classpath엔 없어서 `ClassNotFoundException` 발생.

**정석 해결책**: `@Configuration` → `@TestConfiguration`으로 변경 (적용 완료, `EgovHybridRetrieverToggleTest.java:54`).

`@TestConfiguration`은 `@TestComponent`를 메타 애노테이션으로 가지고 있고, Spring Boot의 메인 컴포넌트 스캔이 `@TestComponent` 붙은 클래스를 **처음부터 스캔 대상에서 제외**하도록 설계되어 있다. 즉 classpath에 bin/test가 섞여 들어가는 근본 문제(Buildship/VSCode의 classpath 구성 방식)는 그대로 남아있지만, 애노테이션을 올바르게 쓰면 그 문제가 실제 장애로 이어지지 않는다. 반대로 순수 `@Configuration`으로 된 테스트 전용 설정 클래스가 있다면 언제든 같은 문제가 재발할 수 있으므로, **테스트에서만 쓰는 `@Configuration`은 항상 `@TestConfiguration`으로 선언할 것.**

이제 Boot Dashboard로 정상 실행 가능하며, `gradlew bootRun`으로 전환할 필요는 없다.

(참고: 만약 이후에도 유사한 문제가 재발하면, Gradle이 직접 앱을 켜고 VSCode는 attach만 하는 방식으로 우회 가능하다. `./gradlew bootRun --debug-jvm` + `launch.json`에 `"request": "attach", "port": 5005` 설정 추가.)

## 9. `gradlew` 실행 관련 트러블슈팅 (PowerShell)

- **`.\` 접두사 필수**: PowerShell은 보안상 현재 폴더의 실행 파일을 이름만으로 못 찾는다. `gradlew.bat bootRun`이 아니라 `.\gradlew.bat bootRun`으로 실행해야 함 (cmd.exe와 다른 점).
- **`JAVA_HOME` 끝에 백슬래시(`\`) 금지**: `gradlew.bat`은 내부적으로 `"%JAVA_HOME%\bin\java.exe"`처럼 따옴표로 감싸 쓰는데, `JAVA_HOME` 값 끝에 `\`가 붙어있으면 이스케이프가 깨져서 `ERROR: JAVA_HOME is set to an invalid directory: "= ...` 같은 알아보기 힘든 에러가 난다. 반드시 끝에 슬래시 없이 설정할 것.

  ```powershell
  $env:JAVA_HOME = "D:\PROJ_KOSII\java\jdk-21.0.11+10"
  ```

- **진행률(`80%` 등)이 멈춰있는 건 정상**: 그 퍼센트는 앱 시작 진행률이 아니라 Gradle 빌드 태스크 그래프의 완료율이다. `bootRun`은 서버가 종료될 때까지 계속 실행 상태로 남는 blocking 태스크라서, 그 지점에서 퍼센트가 영원히 멈춰 보인다. 실제 서버 기동 여부는 퍼센트가 아니라 `Tomcat started on port(s): ...`, `Started Langchain4jRagApplication in ...` 로그로 확인해야 함.
- **로그가 잘려 보이면**: Gradle의 기본 "rich console"이 터미널을 실시간으로 다시 그리면서 화면을 덮어쓰기 때문. `--console=plain`을 붙이면 로그가 그냥 순서대로 쭉 출력되어 스크롤이 정상적으로 남는다.

  ```powershell
  .\gradlew.bat bootRun --console=plain
  ```

- **코드 저장할 때마다 자동 재시작하고 싶으면**: `--continuous` 옵션 (완전한 핫리로드는 아니고 재컴파일 후 재시작).

  ```powershell
  .\gradlew.bat bootRun --continuous
  ```
