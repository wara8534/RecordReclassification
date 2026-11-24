# 기록온 — 기록물 공개분류

Java Spring Boot 백엔드와 순수 HTML / CSS / JavaScript 프론트엔드로 만든 단일 사용자 로컬 웹 애플리케이션입니다.

## 실행

- JDK 17 이상 (권장 JDK 21)
- Maven 3.6.3 이상
- 첫 빌드 시 Maven Central에 접근할 수 있는 인터넷 연결

Windows PowerShell:

```powershell
.\run.ps1
```

실행 정책으로 차단되는 경우:

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

스크립트는 `.tools`의 로컬 JDK / Maven이 있으면 우선 사용합니다. 없으면 설치된 Maven과 `JAVA_HOME`을 사용합니다. 다운로드된 도구와 의존성은 `.tools`에만 보관하며 소스 관리에서 제외합니다.

이미 같은 포트에서 기록온이 실행 중이면 `RecordOn is already running`과 접속 주소를 표시하고 중복 실행하지 않습니다. 이때는 표시된 주소로 접속하면 됩니다. Java 코드를 변경한 뒤에는 기존 서버를 종료하고 다시 실행해야 새 코드가 반영됩니다.

다른 환경 또는 Maven 직접 실행:

```sh
mvn spring-boot:run
```

브라우저에서 **http://localhost:8080** 에 접속하세요. 기본적으로 `127.0.0.1`에서만 요청을 받습니다. 포트 변경은 `PORT` 환경변수로 가능합니다.

```powershell
.\run.ps1 -Test
```

```sh
mvn test
mvn package
java -jar target/record-reclassification-1.0.0.jar
```

## Render 배포

이 프로젝트는 Java 서버가 HTML·JavaScript·글꼴·서식을 함께 제공하는 **하나의 Docker Web Service**로 배포합니다. 프론트엔드 별도 배포나 API 주소 변경, CORS 설정은 필요하지 않습니다.

1. `Dockerfile`, `.dockerignore`, `render.yaml`을 포함한 변경 사항을 GitHub `main`에 커밋·푸시합니다.
2. [Render 대시보드](https://dashboard.render.com/)에서 로그인 후 **New → Blueprint**를 선택합니다.
3. `wara8534/RecordReclassification` 저장소를 연결합니다. 비공개 저장소라면 Render에 해당 저장소 접근 권한을 부여합니다.
4. 저장소 루트의 `render.yaml`을 확인하고 서비스를 생성합니다.
5. 빌드·배포 로그가 성공하고 서비스가 Live 상태가 되면 Render가 표시하는 `https://…onrender.com` 주소로 접속합니다.

수동으로 **New → Web Service**를 사용하는 경우 설정:

| 항목 | 값 |
| --- | --- |
| 저장소 | `https://github.com/wara8534/RecordReclassification` |
| Branch | `main` |
| Language / Runtime | `Docker` |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | `.` |
| Health Check Path | `/api/health` |
| 환경변수 | `SERVER_ADDRESS=0.0.0.0`, `PORT=10000`, `DEBUG=false` |
| Build / Start Command | 별도 입력 없이 Dockerfile 사용 |

Docker 빌드 단계에서 JDK 21과 Maven으로 테스트와 패키징을 실행합니다. 실행 이미지에는 JRE와 JAR만 포함하며 일반 사용자 권한으로 실행합니다. `.tools`, `.git`, `target`, 로컬 로그는 Docker 빌드에 전달하지 않습니다.

`render.yaml`은 무료 플랜으로 명시되어 있습니다. 무료 인스턴스의 유휴 정지·메모리 제한 때문에 첫 접속이 느리거나 큰 엑셀 처리 시 메모리가 부족할 수 있습니다. 대량 파일·동시 접속 운영에는 실측 후 메모리가 더 큰 플랜을 선택하세요. 이 저장소에는 로그인 기능이 없으므로 배포한 URL은 공개 접근 서비스입니다. 실제 비공개 기록물을 운영하려면 별도의 접근제어가 필요합니다.

외부 업로드 파일과 작업 결과를 디스크에 영구 저장하지 않으므로 DB·디스크 서비스는 만들지 않습니다. 원본과 화면 작업 상태는 브라우저 탭에 유지하며, 다운로드 요청 시 서버로 전송합니다.

Docker가 설치된 PC에서는 다음과 같이 같은 이미지를 확인할 수 있습니다.

```powershell
docker build -t record-on .
docker run --rm -p 127.0.0.1:8080:10000 --name record-on record-on
```

브라우저에서 `http://127.0.0.1:8080`을 열고, 종료할 때는 다른 터미널에서 `docker stop record-on`을 실행합니다. 기존 로컬 서버가 8080을 사용 중이면 먼저 종료하세요.

로컬 `run.ps1`은 계속 `127.0.0.1:8080`으로 실행합니다. Docker/Render에서만 환경변수로 외부 바인딩과 포트를 지정합니다.

참고: [Render Docker 배포](https://render.com/docs/docker), [Blueprint 설정](https://render.com/docs/blueprint-spec), [Web Service 포트](https://render.com/docs/web-services).

## 기능

- 문서류·시청각류 일괄등록서식 원본 다운로드
- `.xlsx`, `.xls` 업로드 즉시 자동 분류, ‘결과 다운로드’ 버튼으로 엑셀 저장
- Java / Apache POI 기반 엑셀 읽기·쓰기
- 첨부 정보공개법 제9조 고정 기준으로 기록물철·기록물건의 공개구분 재분류
- 제1항 제1~8호 요건·예외 안내 및 원본 PDF 열람 (읽기 전용)
- 검색, 공개구분·검토상태 필터, 페이지 이동, 분류별 집계
- 기존 공개구분과 추천이 다르면 `검토필요`로 표시합니다. 검토 화면에서 기존 구분, 추천 구분, 판단근거를 확인하고 공개구분·사유·비공개호수·공개제한부분을 수정할 수 있습니다.
- 변경 제안을 검토 완료로 저장하기 전까지는 결과 엑셀에 기존 공개 관련 값을 보존합니다. 검토 완료 항목은 다시 수정할 수 있습니다.
- 재분류 시 검토 완료 항목 보존
- 원본 입력 값·추가 열·다른 시트를 보존한 전체 결과 엑셀 출력 (검색·필터와 무관)

## 엑셀 형식

`입력 양식 다운로드`는 `excel_template/기록물등록_문서류·시청각류일괄등록서식.xlsx`의 원본 복사본을 제공합니다. 앱에 포함되는 파일은 `src/main/resources/templates/records-template.xlsx`입니다. 시트 이름·순서와 상단 4행을 유지하고 기록물철 또는 기록물건 시트의 5행부터 작성하세요.

| 항목 | 기록물철 시트 | 기록물건 시트 |
| --- | --- | --- |
| ID | 기록물철ID | 기록물건ID |
| 제목 (필수) | 제목 | 건제목 |
| 유형·키워드 | 기록유형, 세부유형, 키워드 | 건유형, 세부유형, 키워드 |
| 검토할 본문 | 비고 | 비고 / 주요내용 |
| 생산 정보 | 생산부서명, 생산연도 시작·끝 | 생산일자에서 연도 표시 |
| 결과를 쓰는 위치 | AG 공개구분, AI 비공개호수, AJ 공개제한부분, AK 비공개사유 | Z 공개구분, AB 비공개호수, AC 공개제한부분, AD 비공개사유 |

- 파일은 최대 10MB이며 두 시트 합계 200,000건까지 처리합니다. 시트별 입력 범위는 5행부터 200,004행까지입니다. 빈 ID에는 화면용 행 번호를 표시합니다. 두 시트에 같은 ID나 같은 행 번호가 있어도 각각의 원본 위치로 결과를 돌려보냅니다.
- 읽는 열의 수식은 값으로 붙여넣어 주세요. 제목은 최대 2,000자, 비고·주요내용은 최대 10,000자입니다. 내용이 비어 있으면 화면에서 `검토필요`로 표시합니다.
- 비공개호수는 `1,3` 형식의 문자열로 작성합니다. 공개제한부분은 부분공개 결과에 적용합니다. 공개예정일자, 유효성검사 시트, 원본의 나머지 입력 열과 서식을 보존합니다.
- 등록 서식 결과에는 공개·부분공개·비공개를 기록합니다. 여전히 `검토필요`인 행은 원본의 공개 관련 값을 유지합니다. 판단근거와 검토 완료 상태는 화면에서 확인합니다.
- 결과 다운로드는 `<원본파일명>_분류결과.xlsx`를 생성합니다. 이전 15열 `.xlsx`·`.xls` 서식도 업로드할 수 있으며 업로드한 형식에 맞춰 기존 세 결과 열에 기록합니다.

## 분류 방식과 데이터 수명

사용자가 제공한 `공공기관의정보공개에관한법률_제9조.pdf`의 **2023.11.17 시행 / 법률 제19408호** 버전을 기준으로 합니다. 원문 복사본은 `src/main/resources/static/reference/information-disclosure-article9.pdf`에 있습니다. SHA-256: `E4064294D7E56841E0F6F20F56106C42EC91ABAF0B1050F5AF036AD11426A1C9`.

PDF의 제1항 1~8호를 Java `ClassificationService`의 고정 기준으로 구조화했습니다. PDF를 매 요청마다 읽거나 AI가 해석하는 방식은 아닙니다. 최신 법령 자동 갱신도 하지 않습니다. 화면이나 API에서 사용자 규칙을 지정할 수 없습니다.

제목·기록물분류·내용·키워드를 공백·대소문자 구분 없이 부분 문자열로 검색합니다. 탐지어는 구현상 보조 수단이며 법령에서 정한 판정 규칙이 아닙니다. 여러 조항이 일치하면 각각의 요건과 예외를 근거에 표시합니다. 입력한 원문에 부정 표현이나 동음이의어가 있어도 오탐할 수 있고, 탐지어가 없는 표현은 놓칠 수 있습니다. 결과는 법적 확정 판정이 아닌 규칙 기반 자동분류입니다.

- 내용이 있고 비공개 관련 표현이 탐지되면 **비공개**, 미탐지면 제9조제1항 본문의 공개 원칙에 따라 **공개**로 추천합니다. 기존 공개·부분공개·비공개 구분과 추천이 다르면 변경 방향과 추천 근거를 제시하고 검토를 기다립니다.
- 제6호·제7호 예외 관련 표현, 내부검토·의사결정 종료 또는 제2항의 필요성 소멸 표현이 탐지되면 비공개 추천을 보류합니다. 예외가 성립했다는 확정 판정이 아닙니다.
- 내용이 비어 있거나 예외·종료·비공개 필요성 소멸 관련 표현이 있으면 화면에 **검토필요**로 표시합니다.
- 부분공개는 제9조만으로 자동 결정하지 않습니다. 담당자가 별도 근거와 분리 가능성을 검토해 선택합니다. 실제 원문의 마스킹 기능은 없습니다.
- 제3항·제4항의 기관별 세부 기준 수립·점검 사항을 안내합니다. 첨부 파일에 없는 기관별 기준은 자동 적용하지 않습니다.
- 최종 구분과 사유는 개별 검토에서 결정합니다. 미검토 항목의 의견은 재분류 시 바뀌지만 검토 완료 항목은 보존됩니다.

데이터베이스와 로그인은 포함하지 않았습니다. 원본 파일 바이트와 검토 결과는 브라우저 탭 메모리에 보관하며 새로고침하면 사라집니다. 다운로드 때 원본과 화면에서 수정한 행만 전송합니다. 서버는 일반 모드에서 원본을 다시 분류하고 수정한 값을 적용하며, AI 모드에서는 검토 완료 건을 반영합니다. 고정 분류 기준·PDF·입력 서식은 서버에 유지됩니다. 서버는 요청 처리 중 메모리와 임시 저장소를 사용하며 업로드 기록물을 영구 보관하지 않습니다. 외부 AI 서비스로 전송하지 않습니다. 공유 운영에는 인증·접근제어·영구 저장·감사 이력 추가가 필요합니다.

## 로컬 AI 공개분류 검토

Docker와 Compose를 사용할 수 있는 PC에서 아래 명령을 실행합니다. 기존 기록온 서버가 실행 중이면 종료한 후 시작하세요.

```powershell
.\run.ps1 -Ai
```

Windows에서 Docker가 없으면 [Docker Desktop Windows 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)에 따라 설치한 뒤 Linux 컨테이너 모드로 실행하세요. 실행 중인 Docker가 있는지 `docker compose version`과 `docker info --format '{{.OSType}}'`으로 확인할 수 있습니다. Docker 없이 쓰는 경우 해당 PC에서 Qwen과 추출기를 먼저 실행하면 `-Ai`가 두 로컬 서비스에 연결합니다. 엔진 준비 전에는 `.\run.ps1`으로 규칙 기반 분류를 실행할 수 있습니다.

이 모드는 로컬 Qwen과 추출기에 연결된 기록온을 `http://127.0.0.1:8080`에서 실행합니다. 첫 실행에는 추출기 이미지 빌드와 가중치 다운로드에 인터넷 연결이 필요합니다. Qwen은 컨테이너가 시작될 때, PaddleOCR와 Whisper는 각각을 처음 사용할 때 가중치를 받습니다. 가중치는 Docker 볼륨에 보관합니다. Qwen 시작 상태와 추출기 프로세스는 다음으로 확인합니다.

```powershell
Invoke-RestMethod http://127.0.0.1:8180/health
Invoke-RestMethod http://127.0.0.1:8181/healthz
docker compose -f compose.ai.yaml logs --tail 50
```

`run.ps1`은 `-Ai` 옵션으로 AI 모드를 선택합니다. Java를 직접 실행할 때는 `RECORDON_AI_ENABLED=true` 또는 `false`를 지정합니다. `RECORDON_AI_QWEN_URL`과 `RECORDON_AI_EXTRACTOR_URL`의 기본 주소는 각각 `http://127.0.0.1:8180`, `http://127.0.0.1:8181`입니다. Render에서는 `RECORDON_AI_ENABLED=false`로 실행하세요. 사용이 끝나면 `docker compose -f compose.ai.yaml stop`으로 엔진을 중지할 수 있습니다.

1. 엑셀을 업로드하고 **첨부파일 폴더**에서 `C:/storage/recordon` 또는 해당 엑셀 이름의 폴더를 선택합니다. 처음 작업을 열 때는 어느 쪽을 먼저 선택해도 됩니다. 엑셀을 다른 파일로 바꾸면 첨부 폴더를 다시 선택합니다.
2. 파일을 선택하면 연결 상태가 표시됩니다. **일괄 AI 검토 시작** 또는 개별 화면의 **첨부 포함 AI 검토 시작**을 누르면 검토를 시작합니다. 첨부가 있는 행은 엑셀 입력 정보와 연결된 파일을 함께 검토하므로, 필요한 원문이 포함됐는지 먼저 확인합니다. 첨부가 없는 행은 엑셀 입력 정보로 검토하며, 비고·주요내용이 비어 있으면 제목, 유형, 키워드 등 메타데이터를 근거로 씁니다. 개별 검토 화면에서 원문을 추가할 수 있습니다.
3. 추천 구분, 적용 법령 버전, 검토 범위, 관련 조항, 사유와 근거 위치를 확인합니다. 엑셀 행·열, 상대 파일 경로와 PDF 페이지, 이미지 좌표, 전사 시각을 표시합니다.
4. AI 모드에서는 업로드 영역 아래의 **일괄 AI 검토 시작**을 누른 뒤, 추천이 도착한 행부터 목록과 상단 집계에 표시됩니다. 원문을 확인하고 검토 완료로 저장한 결과를 엑셀에 반영합니다. 미완료 건에는 원본 값을 유지합니다.

### 순번별 첨부 폴더 구조

첨부는 `C:/storage/recordon/<엑셀파일명>/<등록시각>` 아래에 놓습니다. 엑셀 이름 폴더는 `등록.xlsx`와 `등록` 모두 지원하며, 등록시각은 초까지 포함한 `YYYYMMDDHHmmss` 14자리입니다. 일치하는 등록시각이 하나면 자동 연결하고, 여러 개면 화면에서 하나를 선택합니다.

```text
C:/storage/recordon/
└── 등록.xlsx/
    └── 20261122143005/
        └── 1_회의자료철/
            ├── 철표지.pdf
            ├── 철목록.pdf
            └── 1_첫 회의/
                ├── 회의록.pdf
                └── 회의녹음.mp3
```

- `.../1_회의자료철/철표지.pdf`는 기록물철 시트 A열 `순번`이 `1`인 철에 연결합니다.
- `.../1_회의자료철/1_첫 회의/회의록.pdf`는 기록물건 시트 D열 `기록물철 순번`이 `1`이고 E열 `기록물건 일련번호`가 `1`인 건에 연결합니다.
- 철과 건 폴더에서 첫 `_` 뒤의 메모는 매칭에서 제외합니다. `001`과 `1`은 같은 순번입니다. 파일명은 그대로 표시하며 각 폴더 바로 아래의 여러 파일을 모두 해당 행에 연결합니다.

경로가 여러 행에 해당하거나 파일 형식·용량을 확인해야 할 경우 해당 건의 AI 검토를 보류합니다. 폴더는 브라우저에서 연결하고, 파일은 AI 검토를 실행한 건의 요청에만 포함합니다. 첨부가 연결되지 않은 기록물은 개별 검토에서 엑셀의 전문이나 직접 추가한 첨부를 사용할 수 있습니다.

| 엔진 | 처리 범위 |
| --- | --- |
| Qwen3-4B GGUF `Q4_K_M` | 등록된 PDF에서 추출한 제9조 제1항~제4항 원문을 모든 모델 요청에 포함합니다. 제목·유형·생산 정보·키워드·기존 구분과 본문을 함께 분석해 공개·부분공개·비공개·검토필요를 추천합니다. Java가 조항 번호와 근거 위치를 검증합니다. [Qwen 모델 카드](https://huggingface.co/Qwen/Qwen3-4B-GGUF) · [llama.cpp 서버](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md) |
| PaddleOCR PP-OCRv5 경량 한국어 모델 | PNG·JPEG와 스캔·혼합 PDF 페이지에서 본문과 사각형 좌표를 추출합니다. 텍스트 PDF는 페이지별로 텍스트 레이어를 사용합니다. [PaddleOCR](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/OCR.html) |
| Whisper 다국어 `small` | faster-whisper CPU INT8로 첫 번째 음성 트랙을 전사합니다. WAV·MP3·M4A·FLAC과 MP4·MOV·MKV·WebM을 지원합니다. [faster-whisper](https://github.com/SYSTRAN/faster-whisper) |

짧은 기록물은 전체 본문을 한 번에 분석합니다. 긴 기록물은 마스킹한 전문을 최대 8,000자 또는 80개 근거 구간 단위로 나누고, Qwen이 순서대로 검토한 뒤 모든 구간의 의견을 종합합니다. 구간에서 공개 제한 또는 추가 확인이 필요하다고 판단했는데 종합 의견이 전체 공개를 추천하면 보류합니다.

Java는 주민등록번호·이메일·전화번호·일부 계좌번호 패턴을 가린 뒤 Qwen에 보냅니다. 기록물당 첨부 20개, 파일당 25MB, 기록물당 총 100MB, PDF당 20쪽, 매체당 10분까지 처리합니다. 합계 120,000자 또는 24개 분석 묶음을 넘거나 충분히 추출할 수 없으면 검토를 보류합니다. 영상의 음성은 전사하고, 공개 판단에 필요한 화면 정보는 별도 문서로 포함해야 합니다. 원문 첨부의 작업용 파일은 요청 처리 후 삭제됩니다.

등록 법령 PDF를 갱신할 때는 `information-disclosure-article9.txt`, `RegisteredArticle`의 PDF 해시와 `ClassificationService`의 정책 버전도 함께 갱신합니다.

## API

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| GET | `/api/classification-policy` | 첨부 PDF 버전·조항별 요건·예외·보조 탐지어 (읽기 전용) |
| GET | `/api/records/template` | 문서류·시청각류 일괄등록서식 원본 |
| POST | `/api/records/import` | multipart `file` 업로드 및 자동 분류, 화면 표시용 JSON 반환 |
| POST | `/api/records/classify` | JSON `{ records }` 고정 기준 분류 (사용자 `rules` 입력 거부) |
| POST | `/api/records/export` | multipart `file`(원본)과 `edits`(application/json, `{ edits }`) 또는 `records`(application/json, `{ records }`)로 결과 작성 |
| POST | `/api/records/process` | multipart `file` 한 번으로 업로드·자동 분류·결과 엑셀 반환 |
| GET | `/api/records/ai/status` | 로컬 AI 모드 설정 여부 |
| POST | `/api/records/ai/review` | multipart `record`(application/json), `completeRecord=true`, `attachments`(선택)로 전체 검토 추천·근거 위치·검토 범위 반환 |

프로젝트 구조: `src/main/java/kr/records`는 Java API·분류·엑셀 처리, `src/main/resources/static`은 화면, `src/test/java/kr/records`는 테스트입니다.

기술 문서: [Spring Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Apache POI](https://poi.apache.org/).
