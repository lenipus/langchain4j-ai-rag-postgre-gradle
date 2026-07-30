# Kubernetes 배포 운영 절차

`langchain4j-ai-rag-postgre` 모듈을 Kubernetes 클러스터에 배포하는 전체 절차를 안내한다.

---

## 사전 요건

| 항목 | 비고 |
|------|------|
| Docker 28+ | 이미지 빌드용 |
| kubectl | 클러스터 접근 |
| 실행 중인 Kubernetes 클러스터 | 로컬: minikube / kind, 운영: EKS·GKE·AKS 등 |
| 컨테이너 레지스트리 | 이미지 push 가능한 레지스트리 |
| PostgreSQL + PGVector | `postgres-pgvector` 서비스로 접근 가능해야 함 |
| Ollama | 클러스터 내 `ollama` 서비스(11434 포트) 또는 외부 주소 |

---

## 1. 이미지 빌드

```bash
# 프로젝트 루트(pom.xml이 있는 디렉터리)에서 실행
cd langchain4j-ai-rag-postgre

docker build \
  -t <레지스트리>/langchain4j-ai-rag-postgre:1.0.0 \
  .
```

빌드가 완료되면 이미지를 레지스트리에 push한다.

```bash
docker push <레지스트리>/langchain4j-ai-rag-postgre:1.0.0
```

> **참고** — 런타임 베이스 이미지로 `eclipse-temurin:17-jre-jammy`(glibc)를 사용한다.
> DJL `libtokenizers.so` 등 네이티브 라이브러리가 glibc를 요구하므로 musl 기반 alpine 이미지는 사용하지 않는다.

---

## 2. 임베딩 모델 PVC (더 이상 필요 없음)

임베딩을 ONNX 로컬 모델 대신 Ollama(`langchain4j.ollama.*`) 원격 호출로 전환하면서,
`embeddingConfig.json`/`model.onnx` 등을 PVC에 적재하던 절차는 더 이상 필요 없다.
`k8s/models-pvc.yaml`과 `deployment.yaml`의 `models` 볼륨 마운트 자체를 배포에서
제거할지는 별도로 검토가 필요하다(아직 정리 안 됨).

---

## 3. 매니페스트 수정

### deployment.yaml — 이미지 경로 교체

`k8s/deployment.yaml`의 `image` 필드를 push한 이미지 경로로 변경한다.

```yaml
image: <레지스트리>/langchain4j-ai-rag-postgre:1.0.0
```

### configmap.yaml — 환경변수 목록

`k8s/configmap.yaml`에 설정된 환경변수가 실제 클러스터 환경과 일치하는지 확인한다.

| 환경변수 | 기본값 | 대응 프로퍼티 |
|---------|--------|--------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres-pgvector:5432/ragdb` | `spring.datasource.url` |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `org.postgresql.Driver` | `spring.datasource.driver-class-name` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` | `spring.jpa.hibernate.ddl-auto` |
| `PGVECTOR_HOST` | `postgres-pgvector` | `pgvector.host` |
| `PGVECTOR_PORT` | `5432` | `pgvector.port` |
| `PGVECTOR_DATABASE` | `ragdb` | `pgvector.database` |
| `PGVECTOR_USERNAME` | `postgres` | `pgvector.username` |
| `LANGCHAIN4J_OLLAMA_BASE_URL` | `http://ollama:11434` | `langchain4j.ollama.base-url` |
| `MANAGEMENT_HEALTH_PROBES_ENABLED` | `true` | `management.health.probes.enabled` |

> `SPRING_DATASOURCE_*`와 `PGVECTOR_*`는 각각 독립적인 연결 설정이며 둘 다 `postgres-pgvector`를 가리킨다.

> **user.home 분리** — DJL은 네이티브 라이브러리를 `user.home/.djl.ai/`에 캐시한다.
> `readOnlyRootFilesystem: true` 환경에서 쓰기 실패를 막기 위해 `deployment.yaml`의
> `JAVA_TOOL_OPTIONS: -Duser.home=/tmp`로 JVM `user.home`을 쓰기 가능한 `/tmp`(emptyDir)로 지정한다.
> `${HOME}` 치환용 `HOME`(=/models)과 DJL 캐시용 `user.home`(=/tmp)은 서로 다른 용도로 분리되어 있다.

### Secret — DB 인증 정보 생성

DB 비밀번호는 ConfigMap에 평문으로 넣지 않고 Secret으로 관리한다.
`k8s/pgvector-secret.yaml`의 `stringData` 값을 실제 비밀번호로 수정하거나
아래 명령으로 직접 생성한다.

```bash
kubectl create secret generic langchain4j-ai-rag-postgre-db \
  --from-literal=username=<DB_USERNAME> \
  --from-literal=password=<DB_PASSWORD>
```

Secret의 `password` 키는 `SPRING_DATASOURCE_PASSWORD`와 `PGVECTOR_PASSWORD` 양쪽에 주입된다.

---

## 4. 배포

```bash
# PVC 먼저 생성 (임베딩 모델 적재 포함 — 위 2번 참고)
kubectl apply -f k8s/models-pvc.yaml

# Secret 적용
kubectl apply -f k8s/pgvector-secret.yaml

# ConfigMap 적용
kubectl apply -f k8s/configmap.yaml

# Deployment · Service 적용
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

---

## 5. 상태 확인

```bash
# 파드 상태 확인
kubectl get pods -l app.kubernetes.io/name=langchain4j-ai-rag-postgre

# 파드 로그 확인
kubectl logs -l app.kubernetes.io/name=langchain4j-ai-rag-postgre --tail=100

# Deployment 롤아웃 상태
kubectl rollout status deployment/langchain4j-ai-rag-postgre

# Actuator health 확인 (파드 내부에서)
kubectl exec -it <pod-name> -- wget -qO- http://127.0.0.1:8080/actuator/health
```

> **참고** — 초기 구동 시간을 고려해 `readinessProbe.initialDelaySeconds`는 60초로 설정되어 있다.

---

## 6. 접속

Service 타입이 `ClusterIP`이므로 클러스터 외부에서 직접 접근할 수 없다. 아래 방법 중 하나를 선택한다.

### 방법 A: kubectl port-forward (개발·디버그용)

```bash
kubectl port-forward svc/langchain4j-ai-rag-postgre 8080:8080
# 이후 http://localhost:8080 으로 접속
```

### 방법 B: NodePort로 Service 타입 변경 (테스트 환경)

`k8s/service.yaml`에서 `type: ClusterIP`를 `type: NodePort`로 변경하고 재적용한다.

```yaml
spec:
  type: NodePort
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30080   # 30000–32767 범위
```

```bash
kubectl apply -f k8s/service.yaml
# 접속: http://<NodeIP>:30080
```

### 방법 C: Ingress 사용 (운영 환경 권장)

Ingress Controller(nginx 등)가 설치된 경우 Ingress 리소스를 별도로 생성한다.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: langchain4j-ai-rag-postgre
spec:
  rules:
    - host: rag.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: langchain4j-ai-rag-postgre
                port:
                  number: 8080
```

---

## 7. 주요 포트 및 리소스 설정 요약

| 항목 | 값 | 설명 |
|------|----|------|
| 애플리케이션 포트 | `8080` | HTTP, EXPOSE 및 containerPort |
| Readiness probe | `GET /actuator/health/readiness` | 트래픽 수신 준비 여부 (initialDelay: 60s) |
| Liveness probe | `GET /actuator/health/liveness` | 재시작 필요 여부 (initialDelay: 60s) |
| CPU requests/limits | `500m` / `2000m` | |
| Memory requests/limits | `2Gi` / `8Gi` | |
| PostgreSQL 서비스명 | `postgres-pgvector` | 클러스터 내 DNS |
| PostgreSQL 포트 | `5432` | |
| DB 이름 | `ragdb` | |
| Ollama 서비스 | `http://ollama:11434` | 클러스터 내 DNS 기본값 |
| 모델 PVC | `langchain4j-ai-rag-postgre-models` (5Gi) | 더 이상 안 씀 - 제거 검토 필요(2번 참고) |

---

## 8. 업데이트 배포 (롤링 업데이트)

새 이미지를 빌드·push한 후 이미지 태그를 갱신하여 재적용한다.

```bash
docker build -t <레지스트리>/langchain4j-ai-rag-postgre:1.0.1 .
docker push <레지스트리>/langchain4j-ai-rag-postgre:1.0.1

# deployment.yaml의 image 태그 수정 후
kubectl apply -f k8s/deployment.yaml

# 롤아웃 진행 상황 확인
kubectl rollout status deployment/langchain4j-ai-rag-postgre
```

롤백이 필요한 경우:

```bash
kubectl rollout undo deployment/langchain4j-ai-rag-postgre
```

---

## 9. 리소스 정리

```bash
kubectl delete -f k8s/service.yaml
kubectl delete -f k8s/deployment.yaml
kubectl delete -f k8s/configmap.yaml
kubectl delete -f k8s/models-pvc.yaml
kubectl delete secret langchain4j-ai-rag-postgre-db
```
