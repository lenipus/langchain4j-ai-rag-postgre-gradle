# 개발환경 구성 가이드 (IntelliJ + Gradle)

IntelliJ IDEA를 이용해 이 프로젝트(`langchain4j-ai-rag-postgre`, Gradle 기반)의 개발 환경을 처음부터 구성하는 절차. `docs/` 아래 개별 문서들(VSCode/Eclipse/Maven 기준으로 작성된 과거 문서 포함)의 핵심 내용을 IntelliJ + Gradle 기준으로 재정리한 통합 가이드다. 더 깊은 배경/트러블슈팅은 각 절에서 링크하는 개별 문서를 참고한다.

## 목차

- [0. 사전 준비물](#0-사전-준비물)
- [1. IntelliJ 설치](#1-intellij-설치)
- [2. 플러그인 설치](#2-플러그인-설치)
- [3. JDK 설정](#3-jdk-설정)
- [4. Maven 설정 (참고용)](#4-maven-설정-참고용)
- [5. 소스 가져오기 (Git)](#5-소스-가져오기-git)
- [6. Gradle 프로젝트로 구성 및 실행](#6-gradle-프로젝트로-구성-및-실행)
- [7. 공통 인프라 기동 — PostgreSQL(pgvector)](#7-공통-인프라-기동--postgresqlpgvector)
- [8. LLM/임베딩 모델 설치](#8-llm임베딩-모델-설치)
  - [8.1 Ollama 설치](#81-ollama-설치)
  - [8.2 임베딩 모델 설치](#82-임베딩-모델-설치)
  - [8.3 LLM 모델 설치 (Ollama pull)](#83-llm-모델-설치-ollama-pull)
  - [8.4 HuggingFace GGUF 모델 다운로드 → Ollama 등록](#84-huggingface-gguf-모델-다운로드--ollama-등록)
- [9. 실행 확인](#9-실행-확인)
- [부록 1. Git 사용법](#부록-1-git-사용법)
- [부록 2. 루트 폴더의 IDE 전용 파일/폴더](#부록-2-루트-폴더의-ide-전용-파일폴더)
- [부록 3. LLM 모델 파일 포맷과 구동 인터페이스 정리](#부록-3-llm-모델-파일-포맷과-구동-인터페이스-정리)
- [부록 4. VSCode / IntelliJ 주요 단축키](#부록-4-vscode--intellij-주요-단축키)

## 0. 사전 준비물

| 항목 | 버전 | 용도 |
|---|---|---|
| JDK | 21 (`build.gradle`의 toolchain 설정) | 빌드/실행 |
| IntelliJ IDEA | **Community** | 개발 IDE (이 가이드는 Community 기준. Ultimate 전용 기능은 사용하지 않는다) |
| Git | 최신 | 소스 관리 |
| Docker Desktop | 최신 | PostgreSQL(pgvector) 컨테이너 구동 (7절) |
| Docker (WSL, 예: RockyLinux9) | - | Ollama 컨테이너 구동 (8절, Docker Desktop과 별개) |
| Ollama | 0.17.1 이상 (하위 버전은 CVE-2026-7482 취약점) | LLM/임베딩 서빙 |

> Community 에디션에는 Spring/Docker 전용 통합 기능(하단 `Services` 대시보드 등)이 없다. Spring Boot 앱 실행은 클래스 옆 거터의 실행(▶) 아이콘으로 하고, 도커 컨테이너 관리는 IDE 밖에서 직접 한다 — PostgreSQL은 Docker Desktop(7절), Ollama는 WSL 터미널(8절)로 각각 다르게 관리한다.

## 1. IntelliJ 설치

1. [JetBrains 공식 사이트](https://www.jetbrains.com/idea/download/)에서 **IntelliJ IDEA Community Edition** 설치 파일 다운로드
2. 설치 마법사 기본값으로 진행 (Windows 기준 `.exe` 설치)
3. 최초 실행 시 UI 테마/키맵은 취향대로 선택 (기능에 영향 없음). 이때 "VSCode 설정을 가져올까요?" 같은 동기화 제안이 뜨면, 수락 시 VSCode에서 쓰던 키맵/확장 일부(`VSCode Keymap` 플러그인, 활성화해뒀던 확장 등)가 자동 설치된다 — `Settings` → `Plugins`의 "User-installed" 목록에 보이는 항목 중 직접 설치 안 한 게 있다면 대부분 이 경로로 들어온 것.

## 2. 플러그인 설치

`File` → `Settings` → `Plugins`에서 설치/활성화. IntelliJ는 **Java, Gradle, Git, Markdown 지원을 기본 내장**하고 있어서 이 항목들은 `Settings` → `Plugins` → `Installed` 목록에는 있어도 "User-installed"(사용자가 마켓플레이스에서 직접 설치한 것) 목록에는 안 뜬다 — 별도 설치 불필요.

**확인/설치 필요**

| 플러그인 | 비고 |
|---|---|
| **Lombok** | 이 프로젝트가 `compileOnly`/`annotationProcessor`로 Lombok을 쓰므로 **필수**.<br>`Settings` → `Plugins` → `Installed`에서 검색해 이미 있는지 먼저 확인 — 버전에 따라 기본 포함되어 있는 경우도 있다. 없으면 `Marketplace`에서 설치.<br>설치 여부와 별개로, `Settings` → `Build, Execution, Deployment` → `Compiler` → `Annotation Processors`에서 `Enable annotation processing` 체크는 **항상 별도로 켜야** 한다 — 안 하면 `@Getter`/`@RequiredArgsConstructor` 등에서 컴파일 에러가 난다. |

**선택 (추가 설치)**

| 플러그인 | 용도 |
|---|---|
| .env files support | 환경변수 파일 문법 하이라이팅 |

> Docker 관련 플러그인은 Ultimate 전용이라 Community에는 없다. 이 가이드에서는 Docker 컨테이너를 IDE 통합 없이 WSL 터미널에서 직접 다룬다(7·8절).

## 3. JDK 설정

`File` → `Project Structure` (`Ctrl+Alt+Shift+S`) → `SDKs` → `+` → `Add JDK...` → JDK 21 설치 경로 지정.

이 프로젝트는 `gradle.properties`에 아래처럼 JDK 경로가 이미 지정되어 있어, Gradle 빌드 자체는 이 경로를 우선 사용한다:
```properties
org.gradle.java.installations.paths=C:/PROJ_KOSII/java/jdk-21.0.11+10
```
다만 **에디터의 코드 분석/자동완성**은 IntelliJ의 Project SDK 설정을 따로 타므로, 위 `Project Structure`에서도 동일한 JDK 21을 Project SDK로 지정해두는 걸 권장한다 (본인 PC의 실제 JDK 21 설치 경로에 맞게 `gradle.properties` 값도 함께 수정 — 개인 환경마다 경로가 다를 수 있음).

## 4. Maven 설정 (참고용)

이 프로젝트 자체는 **Gradle** 빌드이므로 Maven 설정이 필수는 아니다. 다만 사내 Nexus를 쓰는 다른 Maven 프로젝트를 병행한다면 IntelliJ에도 등록해둔다.

`Settings` → `Build, Execution, Deployment` → `Build Tools` → `Maven`에서 Maven home path, User settings file(`settings.xml`) 경로를 지정.

> 참고: 이 프로젝트의 `build.gradle`/`settings.gradle`은 사내 Nexus(`http://kosiidvlp.iptime.org:11081/nexus/content/groups/public/`)를 **인증 없는 공개(public) 미러**로 직접 참조하고 있어서, Gradle 빌드 자체에는 별도의 `settings.xml`/비밀번호 암호화 절차가 필요 없다. Nexus에 인증이 필요한 별도 Maven 프로젝트를 다룰 때만 아래를 참고한다.

<details>
<summary>Nexus 저장소 비밀번호 암호화 절차 (인증이 필요한 별도 Maven 프로젝트용)</summary>

Maven은 `settings.xml`의 `<servers>`에 저장하는 계정 비밀번호를 평문 대신 암호화된 값으로 저장할 수 있다.

1. 마스터 패스워드 생성 (최초 1회):
   ```powershell
   mvn --encrypt-master-password
   ```
   결과 `{...}` 값을 `settings-security.xml`의 `<master>` 태그에 저장.
2. 실제 비밀번호 암호화:
   ```powershell
   mvn --encrypt-password
   ```
   결과 `{...}` 값을 `settings.xml`의 `<password>`에 저장 (평문 대신).

> Maven은 `settings-security.xml`을 기본적으로 `${user.home}/.m2/settings-security.xml`에서 찾는다. 다른 위치에 두려면 `MAVEN_OPTS=-Dsettings.security=<경로>` 환경변수로 지정해야 하며, 안 잡으면 `지정된 파일을 찾을 수 없습니다` 에러가 난다.

</details>

## 5. 소스 가져오기 (Git)

`File` → `New` → `Project from Version Control...` → 저장소 URL 입력, 또는 터미널에서:
```powershell
git clone <저장소 URL>
```
그 후 IntelliJ에서 `File` → `Open`으로 클론한 폴더를 연다.

Git 기본 사용법은 [부록 1](#부록-1-git-사용법) 참고.

## 6. Gradle 프로젝트로 구성 및 실행

이 프로젝트는 원래 Maven(`pom.xml`) 기반이었다. 즉 git에서 최초로 `pull`/`clone`을 받으면 `pom.xml`만 있고 `build.gradle`은 없는 상태다. 여기서는 그 상태를 가정하고, **Maven 프로젝트를 Gradle 프로젝트로 전환하는 절차**를 기술한다. 이미 `build.gradle`이 저장소에 존재한다면(현재 이 저장소가 그 상태) 6-4 이후만 참고하면 된다.

Gradle 설치 여부와 무관하게, 먼저 `settings.gradle`/`build.gradle`부터 텍스트 파일로 작성한다 (이 단계엔 Gradle이 시스템에 없어도 됨 — 그냥 파일 작성).

### 6-1. `settings.gradle` 작성

```groovy
pluginManagement {
    repositories {
        maven {
            url 'http://kosiidvlp.iptime.org:11081/nexus/content/groups/public/'
            allowInsecureProtocol = true
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = 'langchain4j-ai-rag-postgre'
```

> 참고: `gradle init`이라는 자동 변환 명령도 있다 (`pom.xml`이 있는 폴더에서 실행하면 Gradle이 감지해서 `build.gradle`/`settings.gradle`/`gradlew` 뼈대를 자동 생성). 다만 **이 명령 자체가 시스템에 Gradle이 이미 설치되어 있어야 실행 가능**하고, 자동 변환도 `<dependencies>` 정도만 기계적으로 옮겨줄 뿐 이 프로젝트처럼 **Spring Boot용 커스텀 부모 BOM**(`org.egovframe.boot:egovframe-boot-starter-parent`)을 상속받는 구조는 제대로 못 옮긴다. 그래서 여기서는 아래처럼 직접 작성하는 경로를 기준으로 설명한다.

### 6-2. `build.gradle` 작성 — `pom.xml` 요소 매핑

| `pom.xml` | `build.gradle` | 비고 |
|---|---|---|
| `<parent><artifactId>egovframe-boot-starter-parent</artifactId>...</parent>` | `id 'io.spring.dependency-management'` 플러그인 적용 후 `dependencyManagement { imports { mavenBom '...:5.0.0' } }` | Gradle엔 Maven의 `<parent>` 상속 개념이 없어서, BOM을 "임포트"하는 방식으로 대체 |
| `<properties><java.version>21</java.version>` | `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }` | |
| `<repositories>` | `repositories { maven { url ...; allowInsecureProtocol = true }; mavenCentral(); maven { url ... } }` | |
| `<dependencies>`의 `<dependency>` (기본 scope) | `implementation '<groupId>:<artifactId>'` | |
| `<scope>test</scope>` | `testImplementation '...'` | |
| `<scope>provided</scope>` / `<optional>true</optional>` (Lombok) | `compileOnly '...'` + `annotationProcessor '...'` | |
| `<exclusions><exclusion>...` | `exclude group: '...', module: '...'` | 의존성별 개별 제외 또는 `configurations.configureEach { exclude ... }`로 전역 제외 |
| `spring-boot-maven-plugin` | `id 'org.springframework.boot' version '3.5.6'` 플러그인 | 적용만 하면 `bootJar` 태스크가 자동 생김 |
| `maven-compiler-plugin`의 Lombok `annotationProcessorPaths` | 불필요 | Gradle은 `compileOnly`/`annotationProcessor` 의존성 선언만으로 자동 처리 |

실제로 완성된 `build.gradle` (이 프로젝트의 현재 파일, 참고용 — Spring AI가 아니라 **LangChain4j**를 쓰고, 벡터스토어도 Redis가 아니라 **PGVector 단독**이다):
```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.6'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '1.0.0'
description = 'RAG application using LangChain4j and PostgreSQL with PGVector'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    maven {
        url 'http://kosiidvlp.iptime.org:11081/nexus/content/groups/public/'
        allowInsecureProtocol = true
    }
    mavenCentral()
    maven { url 'https://maven.egovframe.go.kr/maven/' }
}

dependencyManagement {
    imports {
        mavenBom 'org.egovframe.boot:egovframe-boot-starter-parent:5.0.0'
    }
}

configurations.configureEach {
    exclude group: 'org.springframework.boot', module: 'spring-boot-starter-logging'
}

dependencies {
    developmentOnly 'org.springframework.boot:spring-boot-devtools'

    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // LangChain4j Core + Ollama 네이티브 클라이언트 + OpenAI 호환 클라이언트 + PGVector 저장소
    implementation 'dev.langchain4j:langchain4j'
    implementation 'dev.langchain4j:langchain4j-ollama'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.8.0'
    implementation 'dev.langchain4j:langchain4j-pgvector'
    implementation 'dev.langchain4j:langchain4j-embeddings'
    implementation 'dev.langchain4j:langchain4j-document-parser-apache-pdfbox'
    implementation 'dev.langchain4j:langchain4j-reactor'

    implementation('org.egovframe.rte:egovframe-rte-fdl-excel') {
        exclude group: 'org.apache.commons', module: 'commons-lang3'
    }

    testImplementation 'com.h2database:h2:2.2.224'
    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:junit-jupiter'

    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    implementation('org.egovframe.rte:egovframe-rte-ptl-mvc') {
        exclude group: 'org.apache.commons', module: 'commons-lang3'
    }

    implementation 'commons-codec:commons-codec'
    implementation 'kr.dogfoot:hwplib:1.1.9'
    implementation 'kr.dogfoot:hwpxlib:1.0.5'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testCompileOnly 'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

### 6-3. Gradle Wrapper 생성

`build.gradle`/`settings.gradle`은 이제 준비됐지만, 아직 `gradlew`(Wrapper)가 없다. 이걸 만드는 방법은 두 가지다.

**방법 A — 터미널에서 수동으로 (실제로 이 프로젝트가 만들어진 방식)**

1. [Gradle 공식 사이트](https://gradle.org/releases/)에서 배포판(`gradle-9.6.1-bin.zip`) 다운로드
2. `D:\PROJ_KOSII\gradle\gradle-9.6.1`에 압축 해제
3. 어차피 1회성으로만 쓸 것이므로 PATH에 영구 등록하지 말고, **콘솔 창에서 그 세션에만 임시로 PATH를 잡고** 실행한다 (창을 닫으면 사라짐). Gradle은 `JAVA_HOME`도 필요로 하므로 같이 잡아준다:
   ```powershell
   $env:JAVA_HOME = "D:\PROJ_KOSII\java\jdk-21.0.11+10"
   $env:Path = "D:\PROJ_KOSII\gradle\gradle-9.6.1\bin;$env:JAVA_HOME\bin;" + $env:Path
   gradle -v   # 설치 확인
   ```
   > `JAVA_HOME`을 안 잡으면 `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`가 발생한다 (이어서 나오는 `"= 1>&2`, `'""' is not recognized...` 메시지는 `gradle.bat`이 미설정 상태를 처리할 때 나오는 배치스크립트 버그성 출력이라 무시해도 된다 — 원인은 항상 `JAVA_HOME` 미설정).
4. 같은 콘솔 창에서, 프로젝트 루트로 이동해 wrapper 생성:
   ```powershell
   gradle wrapper --gradle-version 9.6.1
   ```

   이 명령이 프로젝트 루트에 아래 파일들을 새로 생성한다:

   ```
   root/
   ├── gradlew                              ← Linux/macOS용 실행 스크립트
   ├── gradlew.bat                          ← Windows용 실행 스크립트
   └── gradle/
       └── wrapper/
           ├── gradle-wrapper.jar           ← wrapper 실행 로직
           └── gradle-wrapper.properties    ← 사용할 Gradle 버전(9.6.1) 명시
   ```

   이후로는 `gradle`이 로컬에 없어도 `.\gradlew.bat`(Windows) / `./gradlew`(macOS/Linux)만으로 정확히 같은 버전의 Gradle로 빌드할 수 있다.

   > 참고: 이 작업이 끝나면 `D:\PROJ_KOSII\gradle\gradle-9.6.1` 폴더는 지워도 무방하다.

**방법 B — IDE에 맡기기 (안 해봐서 검증 안 됨)**

VSCode의 `vscjava.vscode-java-pack`(Extension Pack for Java)에는 **"Gradle for Java"**(`vscjava.vscode-gradle`) 확장이 번들로 포함되어 있다 (IntelliJ도 Gradle 지원이 기본 내장). `build.gradle`이 있는 폴더를 VSCode나 IntelliJ로 열면, 이 확장/기능이 자체 내장된 Gradle Tooling API로 프로젝트를 자동 인식하고 wrapper 파일 생성과 Gradle 배포판 다운로드까지 알아서 처리한다.

**주의**: 이 방식은 **정확한 Gradle 버전을 지정할 방법이 마땅치 않다.** 폴더를 여는 것만으로 끝나는 자동 처리라, IDE/확장이 내부적으로 기본으로 잡아둔 버전을 그대로 쓰게 될 가능성이 높고, 우리가 원하는 특정 버전(예: 9.6.1)으로 딱 맞춰 생성해준다는 보장이 없다. **버전을 정확히 맞춰야 한다면 방법 A를 쓰는 게 확실하다.**

### 6-4. 빌드 확인

```powershell
.\gradlew.bat build
```

에러 없이 끝나면 전환 완료. `pom.xml`은 지우지 않고 남겨둬도 되고(앞서 설명한 대로 병행 운영해도 무방), Gradle만 쓸 거면 이후 `pom.xml`/`pom_boot.xml`/`pom_tomcat.xml`을 지워도 된다.

### 6-5. IntelliJ에서 열기 및 실행

1. `build.gradle`이 있는 폴더를 열면 IntelliJ가 **자동으로 Gradle 프로젝트로 인식**하고 의존성을 내려받는다 (우측 `Gradle` 탭에서 진행 상황 확인 가능).
2. **실행**: `src/main/java/com/example/chat/Langchain4jRagApplication.java` 를 열고, 클래스 옆 거터의 ▶ 아이콘 클릭 → `Run 'Langchain4jRagApplication'`
   - `@SpringBootApplication`이 붙은 클래스는 이 프로젝트에 이거 하나뿐이라, 실행 진입점은 이 클래스가 유일함
   - Community 에디션은 `Services` 대시보드가 없으므로, 재실행/중지도 매번 이 거터 아이콘 또는 상단 Run 버튼으로 한다

3. **필요한 환경변수 (Run Configuration에 설정)**

   `Run` → `Edit Configurations...` → 해당 실행 설정의 `Environment variables`에 추가.

   | 변수 | 기본값(yml) | 언제 오버라이드해야 하나 |
   |---|---|---|
   | `SERVER_PORT` | `8081` | 포트 충돌 시 |
   | `POSTGRES_HOST` / `POSTGRES_PORT` | `127.0.0.1` / `35432` | 프로젝트 `docker-compose.yml` 기본값(35432) 그대로 쓰면 불필요 |
   | `POSTGRES_USER` / `POSTGRES_PASSWORD` | `tester` / `test123#` | DB 계정을 기본값과 다르게 쓸 때 |
   | `OLLAMA_CHAT_BASE_URL` | `http://localhost:31434` | 네이티브 Ollama(포트 11434) 사용 시 필수 |
   | `OLLAMA_CHAT_MODEL_NAME` | (없음) | **항상 지정 필요** — 미지정 시 채팅 모델을 찾지 못함 |
   | `OLLAMA_CHAT_API_KEY` | (없음) | OpenAI 호환 엔드포인트 등 인증이 필요한 서버 사용 시 |
   | `OLLAMA_CHAT_API_TYPE` | `ollama` | 값은 `ollama`(네이티브 API) \| `openai`(OpenAI 호환 API, `base-url`에 `/v1` 포함 필요) 두 가지. 네이티브 Ollama면 기본값 유지 |
   | `OLLAMA_CHAT_NUM_CTX` | `0`(Ollama 기본값 사용) | 컨텍스트 윈도우(`num_ctx`) 크기를 조정할 때 (`api-type=ollama`일 때만 적용) |
   | `OLLAMA_EMBEDDING_BASE_URL` | `http://localhost:31434` | 임베딩 서버를 채팅과 다르게 분리할 때 |
   | `EMBEDDING_PROFILE` | `bgem3` | 임베딩 모델 프로필 전환(`gemma` \| `bgem3`). `model-name`과 PGVector `table-name`/`dimension`/`hash-table-name`이 이 값 하나로 한꺼번에 같이 바뀐다 (수동으로 따로 안 맞춰도 됨) |
   | `OLLAMA_EMBEDDING_API_KEY` | (없음) | OpenAI 호환 엔드포인트 등 인증이 필요한 서버 사용 시 |
   | `OLLAMA_EMBEDDING_API_TYPE` | `ollama` | 값은 `ollama` \| `openai` 두 가지 (의미는 `OLLAMA_CHAT_API_TYPE`과 동일) |
   | `DOCUMENT_UPLOAD_PATH` | `/app/rag/upload` | **항상 자신의 로컬 경로로 재설정 필요** |

4. **콘솔 한글 인코딩(UTF-8)**

   로그에 한글이 깨지면 같은 Run Configuration의 `VM options`에 추가:
   ```
   -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
   ```
   (Windows 시스템 로캘 자체를 UTF-8로 바꾸면 이 옵션 없이도 되지만, 팀원 전체가 그 설정을 켰다는 보장이 없으므로 VM 옵션은 유지 권장)

## 7. 공통 인프라 기동 — PostgreSQL(pgvector)

이 절은 **Docker Desktop** 기준이다. Docker Desktop을 설치하면 Docker Compose(`docker compose` 명령)가 기본 포함되어 있어 별도 설치가 필요 없다.

> Redis Stack과 PostgreSQL(pgvector)이 벡터 저장소로 양자택일 가능하던 시기(Spring AI 기반)의 흔적으로 `docs/docker-compose/redis/`가 아직 남아있지만, 현재 `build.gradle`은 `dev.langchain4j:langchain4j-pgvector`만 의존하고 Redis 벡터스토어 관련 의존성/코드는 전혀 없다. 즉 지금은 **PostgreSQL(pgvector)만 실제로 동작**한다 — Redis 컴포즈는 설치할 필요 없다.

### 7-1. Docker Desktop 설치

1. [Docker 공식 사이트](https://www.docker.com/products/docker-desktop/)에서 Windows용 Docker Desktop 다운로드 후 설치
2. 설치 후 최초 실행 시 WSL2 기반 사용에 동의 (기본값)
3. 설치 확인:
   ```powershell
   docker -v
   docker compose version
   ```

### 7-2. Redis Stack 기동 (현재 미사용 — 참고용)

1. **구성**: compose 파일은 이 저장소의 `docs/docker-compose/redis/`에 있다.

   ```
   redis/
   └── docker-compose.yml    ← 서비스 정의 (비밀번호가 파일에 직접 적혀있음, .env 없음)
   ```

   **`docker-compose.yml`**
   ```yaml
   services:
     redis:
       image: redis/redis-stack:7.4.0-v8
       environment:
         TZ: Asia/Seoul
       ports:
         - "32379:6379"  # Redis 포트
         - "32540:8001"  # RedisInsight 웹 UI 포트
       command:
         - redis-stack-server
         - --requirepass
         - redis
         - --appendonly
         - "yes"
         - --appendfsync
         - everysec
       volumes:
         - ./redis_data:/data
       restart: unless-stopped
   ```

2. **실행**:
   ```powershell
   cd docs\docker-compose\redis
   docker compose up -d
   ```

   | 항목 | 값 |
   |---|---|
   | Redis | `localhost:32379` (비밀번호: 위 파일의 `redis`, 실제로 쓸 땐 직접 바꿔서 사용) |
   | RedisInsight (웹 UI) | `http://localhost:32540` |

### 7-3. PostgreSQL(pgvector) 기동

1. **구성**: compose 파일은 `docs/docker-compose/postgres18/`에 있다.

   ```
   postgres18/
   ├── docker-compose.yml           ← 서비스 정의 (계정 정보가 파일에 직접 적혀있음, .env 없음)
   └── init-scripts/
       └── 01-init-pgvector.sql     ← 최초 기동(빈 데이터 디렉터리) 시 자동 실행되는 초기화 스크립트
   ```

   `01-init-pgvector.sql`은 `vector`/`pg_trgm` 확장 설치, 애플리케이션 테이블(`document_hashes`, `chat_sessions`, `chat_memory`) 및 인덱스 생성까지 한 번에 처리한다. 이미 데이터가 있는 볼륨으로 재기동하면 다시 실행되지 않는다.

   **`docker-compose.yml`**
   ```yaml
   services:
     postgres:
       image: pgvector/pgvector:pg18  # PostgreSQL 18 + PGVector
       container_name: postgres-pgvector
       environment:
         POSTGRES_DB: ragdb
         POSTGRES_USER: postgres
         POSTGRES_PASSWORD: postgres
         POSTGRES_INITDB_ARGS: "-E UTF8 --locale=C"
       ports:
         - "35432:5432"
       volumes:
         - ./postgres_data:/var/lib/postgresql/data
         - ./init-scripts:/docker-entrypoint-initdb.d
       command: postgres -c max_connections=200
       healthcheck:
         test: ["CMD-SHELL", "pg_isready -U postgres"]
         interval: 10s
         timeout: 5s
         retries: 5
       restart: unless-stopped
   ```

2. **실행**:
   ```powershell
   cd docs\docker-compose\postgres18
   docker compose up -d
   ```

   | 항목 | 값 |
   |---|---|
   | PostgreSQL | `localhost:35432` (DB: `ragdb`, 계정: `postgres`/`postgres` — 실제로 쓸 땐 직접 바꿔서 사용) |

### 7-4. 상태 확인

1. 컨테이너 상태 확인:
   ```powershell
   docker ps
   ```
   `redis` 또는 `postgres-pgvector` 컨테이너(택한 쪽)가 `Up`(healthy) 상태면 정상이다.

## 8. LLM/임베딩 모델 설치

이 프로젝트는 임베딩(검색용 벡터 생성)과 답변 생성(LLM)을 분리된 두 모델로 처리하며, **둘 다 Ollama로 통일**되어 있다. 임베딩도 Ollama로 통일한 이유: LLM처럼 로컬/원격 어디든 자유롭게 배치할 수 있고(마이크로서비스 분리 용이), pooling/L2 normalize를 직접 구현할 필요 없이 Ollama가 완성된 벡터를 반환해준다.

| 역할 | 모델 | 실행 방식 |
|---|---|---|
| 임베딩 (Retrieval) | `embeddinggemma:300m` (또는 `bge-m3`) | Ollama가 서빙 → LangChain4j `OllamaEmbeddingModel`이 REST API로 호출 |
| 답변 생성 (Generation) | `qwen3:4b-q4_K_M` | Ollama가 서빙 → LangChain4j `OllamaChatModel`이 REST API로 호출 |

### 8.1 Ollama 설치

[Ollama 공식 사이트](https://ollama.com/download)에서 Windows용 설치 파일 다운로드 후 실행. 별도 설정 없이 설치하면 백그라운드 서비스로 자동 실행되며 기본 포트 `11434`을 쓴다. 모델 저장 위치는 `C:\Users\<사용자>\.ollama\models`(blobs + manifests). 임베딩(8.2)과 LLM(8.3) 모델 둘 다 이 Ollama 위에서 서빙되므로 먼저 설치해야 한다.

### 8.2 임베딩 모델 설치

1. **(과거 방식, 참고용) 로컬 ONNX 직접 설치**

   처음에는 `google/embeddinggemma-300m`을 직접 ONNX로 export해서 Spring AI `TransformersEmbeddingModel`(DJL 기반)로 JVM 안에서 직접 실행했다. 지금은 2번의 Ollama 방식으로 전환했지만, 과거 트러블슈팅 기록으로 남겨둔다.

   Python 환경(uv):
   ```bash
   uv add huggingface-hub transformers optimum[onnx] onnxruntime sentence-transformers
   ```

   `google/embeddinggemma-300m`은 Google의 **gated 모델**이라 HuggingFace 계정으로 라이선스 동의 + 토큰 로그인이 먼저 필요하다.
   ```bash
   uv run hf auth login
   ```

   `sentence-transformers` 래퍼 경로로 export하면 `optimum`/`sentence-transformers` 버전 호환성 버그(`AttributeError: property 'config' of 'SentenceTransformer' object has no setter`)가 발생하므로, `--library-name transformers`로 순수 transformers 경로를 사용해야 한다.
   ```bash
   uv run optimum-cli export onnx \
     --model google/embeddinggemma-300m \
     --library-name transformers \
     --task feature-extraction \
     ./model
   ```

   이 경로는 **pooling이 그래프에 포함되지 않은 상태**(출력은 토큰 단위 `last_hidden_state`)라, 이론적으로는 mean pooling + L2 normalize를 직접 구현해야 하는데, mean pooling은 Spring AI `TransformersEmbeddingModel`이 내장 처리를 담당하고, L2 normalize는 Redis 벡터스토어의 기본 거리 측정 방식인 **COSINE 유사도**가 계산 자체에 정규화를 내포하고 있어 대응하기 때문에, 실제로 별도 구현 로직은 없다.

   당시 `application.yml` 설정:
   ```yaml
   spring:
     ai:
       model:
         embedding: transformers
       embedding:
         transformer:
           onnx:
             modelUri: file:${EMBEDDING_MODEL_PATH:${user.home}/spring-ai-Config/model}/model.onnx
             modelOutputName: last_hidden_state
           tokenizer:
             uri: file:${EMBEDDING_MODEL_PATH:${user.home}/spring-ai-Config/model}/tokenizer.json
             options:
               padding: true
               truncation: true
               maxLength: 512
   ```

   로컬 ONNX 방식은 k8s 배포 시 "JVM 힙에 모델 전체를 올려야 해서 메모리 요구량이 크다"(8Gi 필요)는 단점이 있었다.

2. **ONNX → Ollama 전환**

   `spring-ai-starter-model-transformers` 의존성을 제거하고 `spring-ai-starter-model-ollama` 하나로 채팅+임베딩 둘 다 처리하도록 바꾼다.

   ```xml
   <dependency>
       <groupId>org.springframework.ai</groupId>
       <artifactId>spring-ai-starter-model-ollama</artifactId>
   </dependency>
   ```

   Ollama에 임베딩 모델을 pull:
   ```bash
   ollama pull embeddinggemma:300m
   ```

   `application.yml`을 아래처럼 바꾼다 (`spring.ai.model.embedding`을 `transformers` → `ollama`로 전환하고, `embedding.transformer.onnx` 설정을 `ollama.embedding.model`로 교체):
   ```yaml
   spring:
     ai:
       ollama:
         base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
         embedding:
           model: embeddinggemma:300m

       model:
         embedding: ollama   # transformers → ollama
   ```

   > **이후 추가 전환 (현재 상태)**: 이 프로젝트는 그 뒤 Spring AI 자체를 걷어내고 **LangChain4j**로 다시 전환했다. 그래서 실제 `application.yml`(`src/main/resources/application.yml`)의 키는 위 `spring.ai.*`가 아니라 아래 형태다:
   > ```yaml
   > langchain4j:
   >   ollama:
   >     embedding-model:
   >       base-url: ${OLLAMA_EMBEDDING_BASE_URL:http://localhost:31434}
   >       api-type: ${OLLAMA_EMBEDDING_API_TYPE:ollama}   # ollama(네이티브) | openai(OpenAI 호환)
   >       model-name: ${embedding.profiles.${embedding.active-profile}.model-name}
   > ```
   > "ONNX → Ollama"라는 전환 방향 자체는 동일하고, 그 설정을 읽어 `EmbeddingModel` 빈을 만드는 코드가 Spring AI 자동 구성에서 `EgovLangChain4jConfig.java`의 수동 `@Bean`으로 바뀐 것뿐이다.
   > `model-name`은 이제 `OLLAMA_EMBEDDING_MODEL_NAME`이 아니라 `EMBEDDING_PROFILE`(`gemma` \| `bgem3`)로 전환한다 — PGVector `table-name`/`dimension`/`hash-table-name`도 같은 값을 참조해서 함께 바뀌므로, 모델만 바꾸고 테이블 설정을 안 바꿔서 어긋나는 실수를 막기 위함이다.

   이렇게만 설정하면 Spring AI가 자동으로 `EmbeddingModel` 빈을 만들어준다 — 별도 Java 설정 코드 불필요. 다만 임베딩을 원격 Ollama로 보내면 청크의 **실제 텍스트 내용이 네트워크를 통해 전송**된다는 점은 유의한다 (RAG 특성상 검색된 문서 내용은 이미 LLM 프롬프트로도 전달되므로 새로운 노출 유형은 아니지만, 진짜 원격 서버로 옮길 땐 신뢰 가능한 네트워크나 리버스 프록시 HTTPS 적용을 권장).

### 8.3 LLM 모델 설치 (Ollama pull)

1. **모델 이름 규칙 확인**

   Ollama 태그는 `모델명:태그` 형식이다 (`google/embeddinggemma-300m` 같은 HuggingFace 표기와 다름). 양자화 접미사도 태그 안에 포함된다.
   ```
   qwen3:4b-q4_K_M
   ```
   - `4b` = 파라미터 40억 개
   - `Q4` = 4비트 양자화
   - `_K` = K-quant 알고리즘
   - `_M` = Medium 등급 (S/M/L 중)

   CLI에 `search` 명령이 없으므로, 정확한 태그명은 `https://ollama.com/library/<모델명>/tags` 웹페이지에서 확인해야 한다.

2. **pull 및 확인**

   ```bash
   ollama pull qwen3:4b-q4_K_M
   ollama list   # 확인
   ```

   - 도커로 띄우면 보통 포트를 다르게 매핑(예: `31434`)해서 네이티브와 병행 운영한다. `application.yml`의 `OLLAMA_BASE_URL`이 도커 포트로 잡혀있을 수 있으니, 네이티브만 쓴다면 `11434`로 오버라이드가 필요하다.

3. **동작 방식 참고**

   `ollama pull`은 디스크에 파일만 저장할 뿐 메모리에 바로 로드되지 않는다. 실제 로딩은 **REST 요청이 처음 들어올 때** 자동으로 트리거되고(lazy loading), 유휴 시간(`keep_alive`, 기본 5분) 이후 자동 언로드된다.

4. **커뮤니티(비공식) 모델 설치 시 주의사항 — HyperCLOVA-X 사례**

   Ollama 공식 라이브러리 말고, 커뮤니티가 올린 모델도 `계정명/모델명:태그` 형식으로 그대로 pull할 수 있다.
   ```bash
   ollama pull cookieshake/HyperCLOVA-X-SEED-Vision-Instruct-3B-Llamafied:Q4_K_M
   ```

   이 모델로 채팅하면 답변을 다 하고도 멈추지 않고, `<|eot_id|>`, `<|start_header_id|>user<|end_header_id|>` 같은 특수 토큰이 텍스트로 그대로 노출되면서 같은 내용을 계속 반복 생성하는 증상이 있었다. 원인은 모델의 Modelfile을 확인해보면(`ollama show <모델명> --modelfile`), 프롬프트 템플릿은 Llama-3 방식(`<|eot_id|>`)을 쓰는데, "언제 멈출지" 알려주는 stop 파라미터는 전혀 다른 ChatML(Qwen) 방식으로 잘못 설정되어 있었기 때문이다:
   ```
   TEMPLATE """<|start_header_id|>...<|eot_id|>..."""   ← Llama-3 스타일
   PARAMETER stop <|im_start|>                            ← ChatML 스타일 (템플릿과 안 맞음)
   PARAMETER stop <|im_end|>
   ```
   템플릿이 실제로 만들어내는 토큰(`<|eot_id|>`)과 Ollama가 감시하는 stop 토큰(`<|im_start|>`)이 달라서, 생성이 끝나야 할 시점을 영영 인식 못 하고 계속 새 turn을 만들어내며 무한 반복한 것 — 업로드한 사람의 Modelfile 설정 실수다.

   stop 토큰을 고친 Modelfile을 작성하고 로컬 커스텀 버전으로 다시 `create`하면 해결된다:
   ```bash
   cat > HyperCLOVA.Modelfile << 'EOF'
   FROM cookieshake/HyperCLOVA-X-SEED-Vision-Instruct-3B-Llamafied:Q4_K_M
   PARAMETER stop "<|eot_id|>"
   EOF

   ollama create hyperclova-fixed:q4_k_m -f HyperCLOVA.Modelfile
   ```
   이후 애플리케이션에서는 원본 이름 대신 **`hyperclova-fixed:q4_k_m`**을 모델로 선택하면 정상적으로 한 번에 답변이 끝난다. 도커 인스턴스를 병행한다면 네이티브와 완전히 별개의 모델 저장소이므로, 컨테이너 안에서도 동일하게 pull + Modelfile 작성 + `create`를 반복해야 한다.

### 8.4 HuggingFace GGUF 모델 다운로드 → Ollama 등록

Ollama 공식 라이브러리에 없는 모델(예: 특정 Text2SQL/코드 특화 모델)을 쓰려면, HuggingFace에 커뮤니티/공식이 올린 GGUF 파일을 직접 받아 Ollama에 등록해야 한다.

1. **모델 탐색 기준**

   메모리 제약이 있다면 파라미터 크기 + `Q4_K_M` 양자화(구형 `Q4_0`보다 정확도가 높음) 기준으로 후보를 좁힌다. 검토 기준: 배포 주체(공식 vs 개인), 다운로드/좋아요 수, 학습 데이터셋·벤치마크 공개 여부, 라이선스, 모델 계보. 검증 안 된 소규모 커뮤니티 업로드는 실제로 문제가 생길 수 있다 — 다운로드 25건/좋아요 0개짜리 Text2SQL 파인튜닝 모델을 테스트해본 결과, 정답 SQL 생성 후 멈추지 않고 가짜 메타데이터를 무한 생성하는 문제가 있었고 stop 토큰을 아무리 추가해도 근본 해결이 안 됐던 사례가 있다. 배포 주체가 검증된 모델(공식 배포, 다운로드 많고 벤치마크 공개)을 우선한다.

2. **GGUF 파일 다운로드**

   `uv` 프로젝트로 huggingface_hub CLI(`hf`)를 사용한다. 최신 `huggingface_hub`는 CLI 이름이 `huggingface-cli`에서 **`hf`**로 바뀌었고 서브커맨드 구조(`hf auth login`, `hf download`)로 바뀌었다.

   ```powershell
   mkdir gguf-models
   cd gguf-models
   uv init
   uv add huggingface-hub
   ```

   로그인이 필요한 경우만 (gated 모델이 아니면 보통 불필요):
   ```powershell
   uv run hf auth login
   ```

   다운로드 (예: Qwen2.5-Coder-3B):
   ```powershell
   mkdir qwen-coder
   uv run hf download Qwen/Qwen2.5-Coder-3B-Instruct-GGUF qwen2.5-coder-3b-instruct-q4_k_m.gguf --local-dir ./qwen-coder
   ```

3. **Modelfile 작성**

   모델이 실제로 학습된 채팅 템플릿에 맞춰 `TEMPLATE`/`PARAMETER stop`을 설정해야 한다 (Qwen 계열은 ChatML 형식).

   `gguf-models/qwen-coder/qwen-coder.Modelfile`:
   ```
   FROM ./qwen2.5-coder-3b-instruct-q4_k_m.gguf

   TEMPLATE """<|im_start|>system
   {{ .System }}<|im_end|>
   <|im_start|>user
   {{ .Prompt }}<|im_end|>
   <|im_start|>assistant
   """

   PARAMETER stop "<|im_start|>"
   PARAMETER stop "<|im_end|>"
   PARAMETER temperature 0.4
   ```

   템플릿과 stop 토큰이 실제 모델 포맷과 안 맞으면, 답변이 끝나야 할 시점을 인식 못 하고 특수 토큰이 텍스트로 노출되며 무한 반복하는 증상이 나타난다 (`ollama show <모델명> --modelfile`로 기존 Modelfile 확인 가능).

4. **`ollama create`로 로컬 모델 등록**

   ```powershell
   cd gguf-models\qwen-coder
   ollama create qwen-coder:qwen2.5-3b-q4km-official -f qwen-coder.Modelfile
   ```

   `ollama create` 시 실제 가중치는 Ollama 내부 저장소(`~/.ollama/models`)로 복사되므로, 원본 다운로드 폴더를 나중에 지워도 모델은 남는다. 다만 HuggingFace 원본 저장소 경로·라이선스 같은 출처 정보는 Ollama가 따로 기록하지 않으므로, **태그 이름 자체에 베이스 모델/양자화/배포 주체를 넣어두면**(`qwen2.5-3b-q4km-official` = Qwen2.5, 3B, Q4_K_M, 공식 배포) `ollama list`만 봐도 계보를 알 수 있다. GGUF 파일의 아키텍처/파라미터 수/양자화 레벨 자체는 파일 메타데이터에 내장되어 있어 `ollama show <모델명>`으로 언제든 확인 가능하다.

   네이티브와 도커는 **완전히 별개의 모델 저장소**를 쓰므로, 도커 인스턴스에서도 쓰려면 컨테이너 안에 gguf 파일 + Modelfile을 복사해서 동일하게 `ollama create`를 실행해야 한다.

5. **`ollama run`으로 동작 테스트**

   ```powershell
   ollama run qwen-coder:qwen2.5-3b-q4km-official
   ```

   테스트 질의 (예시):
   ```
   Given the following SQL table schema:

   CREATE TABLE employees (
       id INTEGER PRIMARY KEY,
       name TEXT,
       department TEXT,
       salary INTEGER,
       hire_date DATE
   );

   질문: 2022년 이후에 입사한 'Sales' 부서 직원들의 이름을, 급여가 높은 순서대로 정렬해서 보여줘.

   이 질문에 대한 SQL 쿼리를 생성해줘.
   ```

   정상 등록됐다면 매번 답변이 반복/특수토큰 노출 없이 깔끔하게 끝나야 한다. 이상하면 3번의 Modelfile 템플릿/stop 토큰부터 재점검한다.

## 9. 실행 확인

1. PostgreSQL(pgvector), Ollama 기동 확인
2. 애플리케이션 실행 → `http://localhost:8081` (또는 `SERVER_PORT`로 지정한 포트) 접속
3. 문서 인덱싱은 서버 기동 시 자동 실행되지 않음 — 메인 화면의 `문서 재인덱싱` 버튼 또는 `POST /api/documents/reindex` 수동 호출 필요

---

## 부록 1. Git 사용법

| 하고 싶은 것 | 명령 |
|---|---|
| 변경된 파일 확인 | `git status` |
| 변경 내용(diff) 확인 | `git diff` |
| 커밋 이력 보기 | `git log` (`git log --oneline`로 간단히) |
| 파일 스테이징 | `git add <파일>` (전체: `git add -A`) |
| 커밋 | `git commit -m "메시지"` |
| 특정 파일 변경 취소(되돌리기) | `git checkout -- <파일>` 또는 `git restore <파일>` |
| 스테이징 취소(add만 취소, 파일 내용은 유지) | `git restore --staged <파일>` |
| 마지막 커밋 메시지만 수정 | `git commit --amend` |
| 브랜치 목록 | `git branch` |
| 새 브랜치 만들고 이동 | `git checkout -b <브랜치명>` |
| 브랜치 이동 | `git checkout <브랜치명>` |
| 원격 저장소에서 받아오기 | `git pull` |
| 원격 저장소로 올리기 | `git push` |
| 작업 중이던 변경사항 임시 보관 | `git stash` (복원: `git stash pop`) |
| 특정 커밋 시점으로 완전히 되돌리기 (주의, 이후 이력 삭제됨) | `git reset --hard <커밋해시>` |
| 파일별 변경 이력(blame) | `git blame <파일>` |

**핵심 개념**: `commit`(로컬 저장)과 `push`(원격 반영)가 분리되어 있다. 그래서 커밋은 자주 해도 되고, `push`하기 전까지는 언제든 로컬에서 되돌리거나 정리할 수 있다.

---

## 부록 2. 루트 폴더의 IDE 전용 파일/폴더

같은 저장소를 IntelliJ, VSCode, (과거) 실제 Eclipse로 번갈아 열어봐서, 루트에 각 도구가 각자 만들어둔 설정 파일/폴더가 섞여 있다. `bin/`은 VSCode `redhat.java` 확장(내부 Eclipse JDT 언어서버)이 만드는 컴파일 출력 폴더인 게 직접 확인됐고(`main/`, `test/` 컴파일 산출물 내용으로 확인), `.project`/`.classpath`/`.settings`/`.factorypath`/`.apt_generated*`는 이름은 "Eclipse" 형식인데 VSCode/실제 Eclipse 중 어느 쪽이 만든 건지 이 저장소만으로는 구분이 안 된다 (자세한 케이스별 구분은 아래 상세내역 참고). 어느 게 어떤 도구 것인지 구분해두면 "이거 지워도 되나?" 헷갈릴 때 참고할 수 있다 (전부 `.gitignore` 대상이라 지워도 git 이력엔 영향 없음, 해당 도구가 다시 열 때 자동 재생성됨).

```
root/
├── .apt_generated/            ← 어노테이션 처리 생성 소스 출력
├── .apt_generated_tests/      ← 어노테이션 처리 생성 테스트 소스 출력
├── .gradle/                   ← Gradle 빌드 캐시 (IDE 아님, 빌드 도구 자체)
├── .idea/                     ← IntelliJ 프로젝트 설정
├── .settings/                 ← 세부 설정 (컴파일러 버전 등)
├── .vscode/                   ← VSCode 워크스페이스 설정 (launch.json, settings.json)
├── bin/                       ← VSCode `redhat.java` 확장(내부 Eclipse JDT 언어서버)이 만드는 컴파일 출력 폴더
├── build/                     ← Gradle 빌드 출력 (IDE 아님)
├── target/                    ← Maven 빌드 출력 (IDE 아님)
├── .classpath                 ← 클래스패스 설정
├── .factorypath               ← 어노테이션 프로세서(Lombok 등) 경로 설정
└── .project                   ← 프로젝트 디스크립터 (Buildship이 자동 생성)
```

> `.gradle/`, `build/`, `target/`은 IDE가 아니라 각 **빌드 도구(Gradle/Maven)** 가 만드는 산출물 폴더라 성격이 다르다. 지워도 다음 빌드에 다시 자동생성되는 폴더다.

### 상세내역 — 케이스별 구성

실제로 그 조합을 쓸 때 생기는 걸 전부(IDE 설정 + 빌드 도구 산출물) 그린 것이다.

**IntelliJ + Gradle**
```
root/
├── .idea/      ← IntelliJ 프로젝트 설정
├── .gradle/    ← Gradle 빌드 캐시
└── build/      ← Gradle 빌드 출력
```

**IntelliJ + Maven**
```
root/
├── .idea/    ← IntelliJ 프로젝트 설정
└── target/   ← Maven 빌드 출력
```

**VSCode + Gradle**

```
root/
├── .vscode/    ← VSCode 워크스페이스 설정 (launch.json, settings.json)
├── bin/        ← JDT 언어서버 자체 컴파일 출력 (Gradle의 build/와 별개)
├── .gradle/    ← Gradle 빌드 캐시
└── build/      ← Gradle 빌드 출력
```

**VSCode + Maven**

```
root/
├── .vscode/    ← VSCode 워크스페이스 설정 (launch.json, settings.json)
└── target/     ← Maven 빌드 출력
```

**Eclipse + Gradle** (Buildship)
```
root/
├── .apt_generated/       ← 어노테이션 처리 생성 소스 출력
├── .apt_generated_tests/ ← 어노테이션 처리 생성 테스트 소스 출력
├── .settings/            ← 세부 설정 (컴파일러 버전 등)
├── .gradle/              ← Gradle 빌드 캐시
├── build/                ← Gradle 빌드 출력
├── .classpath            ← 클래스패스 설정
├── .factorypath          ← 어노테이션 프로세서(Lombok 등) 경로 설정
└── .project              ← 프로젝트 디스크립터 (Buildship이 자동 생성)
```

**Eclipse + Maven** (m2e)
```
root/
├── .apt_generated/       ← 어노테이션 처리 생성 소스 출력
├── .apt_generated_tests/ ← 어노테이션 처리 생성 테스트 소스 출력
├── .settings/            ← 세부 설정 (컴파일러 버전 등)
├── target/               ← Maven 빌드 출력 (m2e가 Eclipse 출력 경로도 여기로 맞춤)
├── .classpath            ← 클래스패스 설정
├── .factorypath          ← 어노테이션 프로세서(Lombok 등) 경로 설정
└── .project              ← 프로젝트 디스크립터 (m2e가 자동 생성)
```

---

## 부록 3. LLM 모델 파일 포맷과 구동 인터페이스 정리

로컬 LLM(예: `Arctic-Text2SQL-R1-7B-GGUF`)을 다룰 때 자주 접하는 **파일 포맷 → 추론 엔진 → API 규약** 3단계 구조를 정리한다. 이 프로젝트는 이 중 **GGUF + Ollama + Ollama REST API** 조합을 사용한다 (8.4절 참고).

### 1. 모델 파일 포맷

| 포맷 | 특징 |
|---|---|
| **GGUF** (구 GGML) | llama.cpp 계열 전용 포맷. 가중치 + 토크나이저 + 메타데이터를 파일 하나에 통합. 4bit/5bit/8bit 등 양자화 지원으로 CPU/저사양 GPU에서도 실행 가능 |
| **safetensors** | Hugging Face 표준 저장 포맷. `.bin`(pickle) 대비 임의 코드 실행 위험이 없어 보안상 안전 |
| **.bin (pickle)** | 구형 PyTorch 저장 방식. 역직렬화 시 코드 실행 위험이 있어 safetensors로 대체되는 추세 |
| **ONNX** | 프레임워크 중립 포맷. ONNX Runtime으로 실행하며 JVM 등 비-Python 환경에도 이식 가능 |
| **TensorRT-LLM engine** | NVIDIA GPU 전용 컴파일 포맷. 최고 성능이지만 특정 GPU/드라이버에 종속 |

`Arctic-Text2SQL-R1-7B-GGUF`처럼 파일명 끝에 `GGUF`가 붙으면, llama.cpp 계열 엔진(Ollama, LM Studio, koboldcpp 등)에서 바로 로드 가능하다는 뜻이다.

### 2. 추론 엔진 (파일을 실제로 구동하는 런타임)

| 엔진 | 주 대상 포맷 | 특징 |
|---|---|---|
| **llama.cpp** | GGUF | C++ 경량 실행, CPU 단독 실행 가능 |
| **Ollama** | GGUF | llama.cpp를 래핑, `Modelfile`로 모델 설정(템플릿/stop 토큰 등) 관리, REST API 제공 |
| **vLLM** | safetensors | PagedAttention 기반 고속 배치 서빙, 프로덕션 대량 트래픽용 |
| **Hugging Face TGI** | safetensors | HF 생태계 표준 서빙 서버 |
| **LM Studio / koboldcpp** | GGUF | GUI 기반 로컬 실행 도구 |

### 3. API 규약 (외부 애플리케이션이 호출하는 인터페이스)

- **OpenAI API 스펙** (`/v1/chat/completions`, `/v1/completions`): 사실상 업계 표준. vLLM, TGI, Ollama, LM Studio 대부분이 이 스펙과 호환되는 엔드포인트를 제공해서, OpenAI SDK 코드의 base URL만 바꿔 재사용 가능
- **Ollama 자체 API** (`/api/generate`, `/api/chat`): Ollama 고유 REST 규약. 이 프로젝트의 LangChain4j `OllamaChatModel` / `OllamaEmbeddingModel`(`dev.langchain4j.model.ollama` 패키지, `api-type=ollama`일 때)이 내부적으로 이 API를 호출
- **gRPC 기반**: Triton Inference Server, TensorRT-LLM 등 고성능/대규모 서빙에서 사용

### 4. 이 프로젝트의 흐름

```
GGUF 모델 파일
  → Ollama가 로드 (lazy loading, keep_alive 정책에 따라 유휴 시 언로드)
  → Ollama REST API (/api/chat, /api/generate) 또는 OpenAI 호환 API (/v1/chat/completions)
  → LangChain4j OllamaChatModel/OpenAiChatModel, OllamaEmbeddingModel/OpenAiEmbeddingModel이 호출
    (api-type 값에 따라 분기, EgovLangChain4jConfig.java 참고)
  → 애플리케이션 로직(RAG 파이프라인 등)에서 사용
```

새 GGUF 모델(예: `Arctic-Text2SQL-R1-7B-GGUF`)을 이 프로젝트에 추가하려면:

1. Ollama가 직접 라이브러리에 등록한 모델이 아니라면 `Modelfile`을 작성해 `FROM <원본 GGUF 경로 또는 태그>`로 지정
2. `ollama create <이름> -f Modelfile`로 로컬 등록
3. `application.yml`의 `langchain4j.ollama.chat-model.model-name` (또는 `embedding-model.model-name`) 값을 새 모델 이름으로 변경

커뮤니티 GGUF 모델은 Modelfile의 템플릿/stop 토큰 설정이 잘못된 경우가 있어 무한 반복 등의 증상이 나타날 수 있다 — 실제 트러블슈팅 사례는 8.3절의 "HyperCLOVA-X 사례" 참고.

---

## 부록 4. VSCode / IntelliJ 주요 단축키

파일 탐색/검색/리팩토링 관련 자주 쓰는 단축키를 도구별로 정리한다 (Windows 기준, 기본 키맵). 같은 기능이라도 도구마다 키가 다르니 섞어 쓰지 않도록 주의한다.

### VSCode

**파일 이름으로 찾아서 열기**

| 단축키 | 기능 |
|---|---|
| `Ctrl+P` | 파일명 일부만 입력해도 퍼지 검색으로 찾아줌 (예: `EgovDoc` → 관련 파일들 표시) |

**코드 안에서 심볼(클래스/메서드/변수) 찾기**

| 단축키 | 기능 |
|---|---|
| `Ctrl+Shift+O` | 현재 파일 안의 메서드/필드 목록 |
| `Ctrl+T` | 워크스페이스 전체에서 클래스/메서드 이름으로 검색 |

**전체 텍스트 검색**

| 단축키 | 기능 |
|---|---|
| `Ctrl+Shift+F` | 프로젝트 전체에서 특정 문자열/정규식 검색 (grep 같은 기능) |

**리팩토링 (Java 확장 기준)**

| 단축키 / 방법 | 기능 |
|---|---|
| `F2` | **이름 변경(Rename Symbol)** — 변수/메서드/클래스 이름 바꾸면 프로젝트 전체 사용처가 자동으로 같이 바뀜 |
| `Ctrl+.` | 전구 아이콘 — "Extract to method/variable/constant", "Introduce parameter" 등 컨텍스트별 리팩토링 메뉴 |
| 우클릭 → `Refactor...` | 위와 동일한 리팩토링 옵션에 메뉴로 접근 |

**정의/사용처 찾아가기**

| 단축키 | 기능 |
|---|---|
| `F12` | 정의로 이동 (Go to Definition) |
| `Shift+F12` | 이 심볼이 어디서 쓰이는지 전부 찾기 (Find All References) |
| `Ctrl+클릭` | 정의로 바로 이동 (F12와 동일) |
| `Alt+←` / `Alt+→` | 이전/다음 커서 위치로 돌아가기 (탐색 히스토리) |

### IntelliJ

**파일 이름으로 찾아서 열기**

| 단축키 | 기능 |
|---|---|
| `Ctrl+Shift+N` | 파일명 일부만 입력해도 찾아주는 Go to File |
| `Shift` 두 번 | Search Everywhere — 파일/클래스/액션/설정까지 통합 검색 |

**코드 안에서 심볼(클래스/메서드/변수) 찾기**

| 단축키 | 기능 |
|---|---|
| `Ctrl+F12` | 현재 파일 안의 메서드/필드 목록 (File Structure) |
| `Ctrl+N` | 워크스페이스 전체에서 클래스 이름으로 검색 (Go to Class) |
| `Ctrl+Alt+Shift+N` | 클래스뿐 아니라 메서드/변수 등 모든 심볼 검색 (Go to Symbol) |

**전체 텍스트 검색**

| 단축키 | 기능 |
|---|---|
| `Ctrl+Shift+F` | 프로젝트 전체에서 특정 문자열/정규식 검색 (Find in Path) |

**리팩토링**

| 단축키 / 방법 | 기능 |
|---|---|
| `Shift+F6` | **이름 변경(Rename)** — 변수/메서드/클래스 이름 바꾸면 프로젝트 전체 사용처가 자동으로 같이 바뀜 |
| `Alt+Enter` | 전구 아이콘 — Quick Fix 및 "Extract Variable/Method" 등 컨텍스트별 인텐션 메뉴 |
| `Ctrl+Alt+Shift+T` | 리팩토링 전체 목록 팝업 (Refactor This) |
| 우클릭 → `Refactor` | 위와 동일한 리팩토링 옵션에 메뉴로 접근 |

**정의/사용처 찾아가기**

| 단축키 | 기능 |
|---|---|
| `Ctrl+B` | 정의로 이동 (Go to Declaration) |
| `Alt+F7` | 이 심볼이 어디서 쓰이는지 전부 찾기 (Find Usages) |
| `Ctrl+클릭` | 정의로 바로 이동 (`Ctrl+B`와 동일) |
| `Ctrl+Alt+←` / `Ctrl+Alt+→` | 이전/다음 커서 위치로 돌아가기 (탐색 히스토리) |
