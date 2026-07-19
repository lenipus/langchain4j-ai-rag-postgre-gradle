# LLM 모델 파일 포맷과 구동 인터페이스 정리

로컬 LLM(예: `Arctic-Text2SQL-R1-7B-GGUF`)을 다룰 때 자주 접하는 **파일 포맷 → 추론 엔진 → API 규약** 3단계 구조를 정리한다. 이 프로젝트(`langchain4j-ai-rag-postgre`)는 이 중 **GGUF + Ollama + Ollama REST API** 조합을 사용한다 ([[embedding-and-ollama-setup]] 참고).

## 1. 모델 파일 포맷

| 포맷 | 특징 |
|---|---|
| **GGUF** (구 GGML) | llama.cpp 계열 전용 포맷. 가중치 + 토크나이저 + 메타데이터를 파일 하나에 통합. 4bit/5bit/8bit 등 양자화 지원으로 CPU/저사양 GPU에서도 실행 가능 |
| **safetensors** | Hugging Face 표준 저장 포맷. `.bin`(pickle) 대비 임의 코드 실행 위험이 없어 보안상 안전 |
| **.bin (pickle)** | 구형 PyTorch 저장 방식. 역직렬화 시 코드 실행 위험이 있어 safetensors로 대체되는 추세 |
| **ONNX** | 프레임워크 중립 포맷. ONNX Runtime으로 실행하며 JVM 등 비-Python 환경에도 이식 가능 |
| **TensorRT-LLM engine** | NVIDIA GPU 전용 컴파일 포맷. 최고 성능이지만 특정 GPU/드라이버에 종속 |

`Arctic-Text2SQL-R1-7B-GGUF`처럼 파일명 끝에 `GGUF`가 붙으면, llama.cpp 계열 엔진(Ollama, LM Studio, koboldcpp 등)에서 바로 로드 가능하다는 뜻이다.

## 2. 포맷 변환 방법 (이미 배포된 모델을 다른 포맷으로 바꾸기)

HuggingFace에 올라온 모델은 대부분 원본이 **safetensors**(또는 구형 `.bin`)다. 여기서 GGUF/ONNX 등 다른 포맷으로 바꾸려면 아래 절차를 거친다. `qllama/bge-m3`처럼 Ollama 라이브러리에 이미 변환돼서 올라온 모델은 이 과정을 생략하고 바로 pull하면 된다([[embedding-and-ollama-setup]] 참고).

### safetensors → GGUF

llama.cpp 저장소의 변환 스크립트로 f16(무손실에 가까운) GGUF를 먼저 만들고, 그 다음 원하는 비트 수로 양자화한다.

**pip 기준**
```bash
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
pip install -r requirements.txt

# 1) HF safetensors 저장소 → f16 GGUF
python convert_hf_to_gguf.py <HF 모델 로컬 경로 또는 repo id> --outfile model-f16.gguf --outtype f16

# 2) f16 → 원하는 양자화 비트로 압축 (llama-quantize는 cmake 빌드 시 같이 생성됨)
./llama-quantize model-f16.gguf model-q4_k_m.gguf Q4_K_M
```

**uv 기준** (가상환경을 따로 안 잡아도 `uv run`이 그때그때 격리된 환경에서 실행)
```bash
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
uv init
uv add -r requirements.txt

# 1) HF safetensors 저장소 → f16 GGUF
uv run convert_hf_to_gguf.py <HF 모델 로컬 경로 또는 repo id> --outfile model-f16.gguf --outtype f16

# 2) f16 → 원하는 양자화 비트로 압축 (llama-quantize는 cmake 빌드 시 같이 생성되는 네이티브 바이너리라 uv와 무관)
./llama-quantize model-f16.gguf model-q4_k_m.gguf Q4_K_M
```

양자화 타입 이름(`Q4_K_M`, `Q8_0` 등)의 의미는 [8.3절의 모델 이름 규칙](./개발환경_gradle.md#83-llm-모델-설치-ollama-pull) 참고. 변환이 끝난 `model-q4_k_m.gguf`는 [8.4절](./개발환경_gradle.md#84-huggingface-gguf-모델-다운로드--ollama-등록)의 `Modelfile` 작성 → `ollama create` 절차로 Ollama에 등록한다.

### safetensors → ONNX

**pip 기준**
```bash
pip install optimum[onnx] onnxruntime
optimum-cli export onnx --model <HF repo id> --task <task> ./output
```

**uv 기준**
```bash
uv add optimum[onnx] onnxruntime
uv run optimum-cli export onnx --model <HF repo id> --task <task> ./output
```

`--task`는 용도에 맞게 지정(`feature-extraction`=임베딩, `text-generation`=생성형 등). 과거 embeddinggemma를 이 방식으로 ONNX 변환했던 실제 사례와 트러블슈팅은 [개발환경_gradle.md 8.2절](./개발환경_gradle.md#82-임베딩-모델-설치) 참고.

### .bin(pickle) → safetensors

변환 스크립트(`convert.py`) 자체는 동일하고, 실행 전 의존성 설치/실행 방식만 다르다.

```python
# convert.py
from safetensors.torch import save_file
import torch

state_dict = torch.load("pytorch_model.bin", map_location="cpu")
save_file(state_dict, "model.safetensors")
```

**pip 기준**
```bash
pip install safetensors torch
python convert.py
```

**uv 기준**
```bash
uv add safetensors torch
uv run convert.py
```

대부분의 최신 HF 모델은 처음부터 safetensors로 배포되므로, 이 변환이 필요한 경우는 오래된 모델을 다룰 때 정도로 드물다.

### TensorRT-LLM engine 빌드

NVIDIA GPU에 종속적인 컴파일 산출물이라 변환 난이도가 가장 높고(TensorRT-LLM 자체 빌드 툴체인 + 특정 GPU/드라이버 버전 고정 필요), 이 프로젝트처럼 로컬/사내망 CPU 위주 환경에서는 실익이 적어 실제로 다뤄본 적은 없다. NVIDIA 공식 `trtllm-build` 문서를 참고해야 하는 영역이라는 정도로만 기록해둔다.

## 3. 추론 엔진 (파일을 실제로 구동하는 런타임)

| 엔진 | 주 대상 포맷 | 특징 |
|---|---|---|
| **llama.cpp** | GGUF | C++ 경량 실행, CPU 단독 실행 가능 |
| **Ollama** | GGUF | llama.cpp를 래핑, `Modelfile`로 모델 설정(템플릿/stop 토큰 등) 관리, REST API 제공 |
| **vLLM** | safetensors | PagedAttention 기반 고속 배치 서빙, 프로덕션 대량 트래픽용 |
| **Hugging Face TGI** | safetensors | HF 생태계 표준 서빙 서버 |
| **LM Studio / koboldcpp** | GGUF | GUI 기반 로컬 실행 도구 |

## 4. 엔진별 설치 및 구동 방법

케이스별로 필요할 때 따라 하기 위한 최소 절차만 정리한다. Ollama는 이미 [개발환경_gradle.md 8절](./개발환경_gradle.md#8-llm임베딩-모델-설치)에서 자세히 다뤘으므로 여기서는 다른 엔진 위주로 적는다.

### llama.cpp — 가장 가벼운 GGUF 실행기

**설치**: [GitHub Releases](https://github.com/ggml-org/llama.cpp/releases)에서 OS에 맞는 사전 빌드 바이너리(`llama-server`, `llama-cli` 포함)를 받거나, 직접 빌드:
```bash
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
cmake -B build
cmake --build build --config Release
```

**구동** (REST 서버 모드):
```bash
llama-server -m model.gguf --port 8080
```
`http://localhost:8080/v1/chat/completions`(OpenAI 호환)와 자체 `/completion` 엔드포인트를 동시에 제공한다. Ollama가 내부적으로 이 엔진을 래핑한 것이므로, Ollama 없이 llama.cpp를 직접 쓰면 `Modelfile` 없이 커맨드라인 옵션(`--chat-template` 등)으로 템플릿을 직접 지정해야 한다.

### vLLM — 대량 트래픽용 고속 서빙

**주의**: 공식적으로 Linux 전용이다(CUDA 필요). Windows에서 쓰려면 WSL2 + NVIDIA GPU 패스스루가 필요하고, 이 프로젝트의 로컬 개발환경(7·8절 기준)과는 별개로 준비해야 한다.

**설치**:
```bash
pip install vllm
```

**구동**:
```bash
vllm serve <HF repo id 또는 로컬 safetensors 경로> --port 8000
```
`http://localhost:8000/v1/chat/completions`로 OpenAI 규약만 제공한다(자체 프로토콜 없음) — `langchain4j.ollama.chat-model.api-type=openai`로 바로 붙일 수 있는 대상이다.

### Hugging Face TGI — HF 생태계 표준 서빙 서버

**설치/구동** (Docker 이미지로 배포되는 게 표준):
```bash
docker run --gpus all -p 8080:80 \
  -v ~/.cache/huggingface:/data \
  ghcr.io/huggingface/text-generation-inference \
  --model-id <HF repo id>
```
`/generate`(TGI 자체 규약)와 `/v1/chat/completions`(OpenAI 호환, 최근 버전에 추가됨)를 함께 제공한다. GPU 없이 CPU로도 뜨지만 느리다.

### LM Studio — GUI 기반 로컬 실행

1. [공식 사이트](https://lmstudio.ai/)에서 Windows용 설치 파일 다운로드 후 실행
2. 앱 내 검색창에서 HF 모델(GGUF) 검색 → 다운로드 (내부적으로 HuggingFace에서 받아옴)
3. `Developer` 탭 → `Start Server` 토글 → `http://localhost:1234/v1`로 OpenAI 호환 엔드포인트 활성화

Ollama처럼 커맨드라인 없이 GUI로 모델 관리/서버 구동을 다 처리하고 싶을 때 적합하다.

### koboldcpp — 단일 실행 파일

1. [GitHub Releases](https://github.com/LostRuins/koboldcpp/releases)에서 `koboldcpp.exe`(Windows) 단일 파일 다운로드
2. 실행:
   ```powershell
   .\koboldcpp.exe --model model.gguf --port 5001
   ```
3. `http://localhost:5001`에 자체 웹 UI가 뜨고, `/v1/chat/completions`(OpenAI 호환)도 같은 포트에서 제공

설치 없이 실행 파일 하나로 끝나서, 다른 PC에서 빠르게 테스트해볼 때 편하다.

## 5. API 규약 (외부 애플리케이션이 호출하는 인터페이스)

- **OpenAI API 스펙** (`/v1/chat/completions`, `/v1/completions`): 사실상 업계 표준. vLLM, TGI, Ollama, LM Studio 대부분이 이 스펙과 호환되는 엔드포인트를 제공해서, OpenAI SDK 코드의 base URL만 바꿔 재사용 가능
- **Ollama 자체 API** (`/api/generate`, `/api/chat`): Ollama 고유 REST 규약. 이 프로젝트의 LangChain4j `OllamaChatModel` / `OllamaEmbeddingModel`(`dev.langchain4j.model.ollama` 패키지, `api-type=ollama`일 때)이 내부적으로 이 API를 호출
- **gRPC 기반**: Triton Inference Server, TensorRT-LLM 등 고성능/대규모 서빙에서 사용

## 6. 이 프로젝트의 흐름

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

커뮤니티 GGUF 모델은 Modelfile의 템플릿/stop 토큰 설정이 잘못된 경우가 있어 무한 반복 등의 증상이 나타날 수 있다 — 실제 트러블슈팅 사례는 [[embedding-and-ollama-setup]] 문서의 "HyperCLOVA-X 사례" 참고.