# Qwen3-4B 공개재분류 미세조정

공공기록물법 제35조의 이관 전 재분류, 비공개 기록물의 정기 재분류, 30년 공개 원칙과 예외, 연장 심의, 생산기관 의견 청취를 가상 기록물에 적용합니다. 공개 범위 판단에는 정보공개법 제9조의 비공개 대상과 제14조의 부분 공개 기준을 함께 사용했습니다.

- [공공기록물법 제35조, 법률 제21066호, 2025년 10월 1일 시행](https://www.law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1028019781)
- [정보공개법 제9조 및 제14조, 법률 제19408호, 2023년 11월 17일 시행](https://www.law.go.kr/LSW/lsInfoP.do?ancYnChk=0&lsId=001357)

`train.jsonl`의 16건과 `evaluation.jsonl`의 평가용 8건은 조문을 바탕으로 작성한 합성 사례입니다. 현재 평가는 분류 결과, 정보공개법 제9조의 적용 호수, 근거 ID, 관련 조문 언급 여부를 확인합니다. 실제 기록물에서의 정확도와 개별 사유의 법률적 타당성은 별도 검토가 필요합니다.

## 재현

Python 3.12와 CUDA를 사용합니다. RTX 4060에서는 PyTorch 2.7.1 CUDA 12.6 빌드, Transformers 4.57.6, PEFT 0.18.1, Accelerate 1.12.0, bitsandbytes 0.49.2를 사용했습니다. `--base`에는 공식 Qwen3-4B Safetensors 및 토크나이저가 있는 로컬 폴더를 지정합니다. [학습에 사용한 Qwen3-4B 리비전](https://huggingface.co/Qwen/Qwen3-4B/tree/1cfa9a7208912126459214e8b04321603b3df60c)

```powershell
.\target\qwen-legal\venv\Scripts\python.exe .\ai-training\public-records\train.py `
  --base .\target\qwen-legal\base --output .\target\qwen-legal\adapter --epochs 4
```

학습이 끝나면 출력 폴더에 PEFT 어댑터와 기본 모델 대비 평가 결과인 `evaluation.json`이 생성됩니다. 학습과 평가는 로컬 가중치를 읽습니다.

## GGUF 어댑터 시험 실행

Windows 로컬 Qwen 설치의 `models/public-records-article35`에 GGUF 어댑터와 비교 평가 결과가 있습니다. 다음 명령은 Qwen 폴더에서 CPU 시험 서버를 포트 8182로 실행합니다.

```powershell
.\runtime\llama-server.exe --model .\models\Qwen3-4B-Q4_K_M.gguf `
  --lora .\models\public-records-article35\recordon-article35-f16.gguf `
  --host 127.0.0.1 --port 8182 --alias recordon-qwen --n-gpu-layers 0 `
  --ctx-size 32768 --parallel 1 --no-context-shift --jinja --offline
```
