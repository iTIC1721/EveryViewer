---
name: git-workflow
description: EveryViewer(옛 가칭 TabPDF) 저장소의 Git 관리 규약(브랜치 전략, 커밋 메시지 규약, 머지·태그 절차). 브랜치를 만들거나, 커밋하거나, main에 머지하거나, 버전 태그를 붙이는 등 Git 작업을 하기 전에 반드시 읽는다.
---

# EveryViewer Git 관리 규약

원격 저장소: `origin` = https://github.com/iTIC1721/EveryViewer (비공개).

- 올리는 것은 `main`과 버전 태그다. 머지 뒤 `git push origin main`, 태그를 붙이면 `git push origin <태그>`.
- 작업 브랜치는 사용자가 원할 때만 올린다.
- 강제 푸시(`--force`, `--force-with-lease`)와 원격 브랜치·태그 삭제는 하지 않는다.
- 작업 시작 전 `git fetch`로 원격에 로컬에 없는 커밋이 있는지 본다. 있으면 사용자에게 알리고 `main`을 맞춘 뒤 브랜치를 만든다.
- 사용자 견본 문서, 서명 파일 등 3장의 커밋 금지 파일은 원격에도 올라가므로 특히 주의한다.

## 1. 브랜치 전략

| 브랜치 | 용도 | 규칙 |
|---|---|---|
| `main` | 항상 빌드되는 안정 상태 | 직접 커밋하지 않는다. 작업 브랜치를 머지해서만 바뀐다. |
| `feature/<주제>` | 새 기능 | 예: `feature/prototype-viewer`, `feature/session-restore` |
| `fix/<주제>` | 버그 수정 | 예: `fix/zoom-edge-swipe` |
| `docs/<주제>` | 문서만 변경 | 예: `docs/update-spec` |
| `chore/<주제>` | 빌드 설정, 의존성, 도구 | 예: `chore/bump-agp` |

- 브랜치 이름은 영어 소문자와 `-`만 쓴다 (kebab-case).
- 작업 브랜치는 항상 최신 `main`에서 만든다: `git switch -c feature/<주제> main`
- 하나의 브랜치에는 하나의 주제만 담는다. 기획서의 개발 단계(9장) 하나가 대략 브랜치 하나다.
- 예외: 저장소 최초 커밋(초기화)만 `main`에 직접 한다.

## 2. 커밋 메시지 규약

[Conventional Commits](https://www.conventionalcommits.org/) 형식을 따르되, **제목과 본문은 한국어**로 쓴다.

```
<type>(<scope>): <제목>

<본문: 무엇을, 왜 바꿨는지. 어떻게는 코드가 말한다.>

Co-Authored-By: ... (Claude가 작성한 커밋일 때)
```

### type
| type | 의미 |
|---|---|
| `feat` | 사용자에게 보이는 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 코드 구조 개선 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 (기획서, CLAUDE.md, 스킬 포함) |
| `build` | Gradle 설정, 의존성, 버전 |
| `chore` | 그 밖의 잡무 (.gitignore, 도구 설정 등) |
| `style` | 포맷팅 등 의미 없는 변경 |

### scope (선택, 하나만)
`viewer`(문서 표시·확대), `tabs`(탭·스와이프), `groups`(탭 그룹), `session`(상태 저장·복원), `pdf`(PDF 렌더링), `formats`(형식 판별·해석·조판), `ink`(필기), `ui`(공통 UI·테마), `data`(DB·모델), `build`, `git`, `spec`(기획서)

### 제목 규칙
- 50자 이내, 마침표 없음, "~한다/~함" 대신 **명사형 또는 "~ 추가/수정/제거"** 로 끝낸다.
  - 좋음: `feat(tabs): 좌우 스와이프로 탭 전환 추가`
  - 나쁨: `탭 기능 만들었습니다.`, `update`, `WIP`
- 하나의 커밋 = 하나의 논리적 변경. 빌드가 깨지는 중간 상태는 커밋하지 않는다.
- 동작이 바뀌는 결정(제스처 규칙 등)은 본문에 이유를 남긴다.

### 커밋 전 확인
1. `git status`로 의도한 파일만 스테이징됐는지 확인 (`git add -A` 남발 금지, 파일을 지정해서 추가).
2. 코드 변경이 있으면 `./gradlew assembleDebug`가 통과해야 한다.
3. 다음은 절대 커밋하지 않는다: `local.properties`, `keystore.properties`, `*.jks`, `*.keystore`, `build/`, `.gradle/`, `.idea/workspace.xml` 등 개인 환경 파일.

### Windows에서 한글 커밋 메시지
PowerShell에서 `-m "한글"`은 인코딩이 깨질 수 있다. 메시지를 UTF-8 파일로 쓰고 `-F`로 넘긴다.

```powershell
git commit -F <메시지파일 경로>
```

메시지 파일은 저장소 밖, **경로가 짧은 곳**에 만든다 (예: `%TEMP%\tabpdf-commit-msg.txt`). Git for Windows는 경로가 260자를 넘으면 `Filename too long`으로 실패한다.

## 3. 머지 절차

1. 작업 브랜치에서 빌드 통과 확인.
2. **사용자에게 머지해도 되는지 확인을 받는다.** (Claude는 확인 없이 main에 머지하지 않는다.)
3. `git switch main` → `git merge --no-ff feature/<주제>`
   - `--no-ff`로 머지 커밋을 남겨 기능 단위 이력이 보이게 한다.
   - 머지 커밋 메시지: `merge: <브랜치 요약>` (예: `merge: 뷰어·탭 스와이프 프로토타입`)
4. 머지한 브랜치는 삭제: `git branch -d feature/<주제>`
5. 리베이스(`rebase`)와 강제 이력 수정(`reset --hard`, `commit --amend`로 이미 머지된 커밋 수정)은 하지 않는다. 아직 머지되지 않은 작업 브랜치의 마지막 커밋을 고치는 것은 허용.

## 4. 버전과 태그

- [SemVer](https://semver.org/lang/ko/) `vMAJOR.MINOR.PATCH`. 출시 전에는 `v0.x.y`.
- 기획서 개발 단계를 마칠 때 `main`에 태그를 붙인다 (사용자 확인 후).

| 태그 | 시점 |
|---|---|
| `v0.1.0` | 1단계: 뷰어 + 스와이프 탭 전환 프로토타입 |
| `v0.2.0` | 2단계: 세션 복원 |
| `v0.3.0` | 3단계: 탭 전환 화면 + 그룹 관리 |
| `v0.4.0` | 4단계: 남은 MVP 기능, 예외 처리, 측정, 계측 테스트 |
| `v0.5.0` | 5단계: 문서 틀 일반화 + 텍스트·마크다운·이미지 |
| `v0.6.0` | 6단계: 필기 |
| `v0.7.0` | 7단계: docx, hwpx |
| `v0.8.0` | 8단계: pptx, 마무리 |
| `v0.9.0` | 2차: 텍스트 검색 |
| `v0.10.0` | 2차: 텍스트 선택·복사 |
| `v0.11.0` | 실기기 피드백(다크 모드, 설정 화면, 그룹 끌어내기), 큰 텍스트 파일, 보안 점검, PDF 링크 |
| `v1.0.0` | 첫 출시 — **사용자가 출시를 언급할 때만.** 그 전에 실기기 확인(핀치, 파일 선택기, 끌기 판정, 탭 넘김, 펜 필기)을 마친다 |

- 태그는 주석 태그로: `git tag -a v0.1.0 -m "v0.1.0: <요약>"`
- 태그를 붙일 때 `app/build.gradle.kts`의 `versionName`도 같은 값으로, `versionCode`는 1씩 올린다 (별도 `build:` 커밋).

## 5. 작업 흐름 요약 (Claude용 체크리스트)

```
작업 시작 → git status 깨끗한지, git fetch로 원격과 같은지 확인 → git switch -c <type>/<주제> main
작업 중   → 논리 단위마다 빌드 확인 후 규약대로 커밋
작업 완료 → 결과 보고 + 머지 여부를 사용자에게 확인
머지      → --no-ff 머지 → 브랜치 삭제 → git push origin main → (단계 완료 시) 태그 제안 → 태그 push
```
