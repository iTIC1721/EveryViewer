# TabPDF (멀티 탭 PDF 뷰어)

여러 PDF를 탭으로 열어두고 좌우 스와이프로 넘기며, 탭 그룹으로 관리하는 가벼운 Android PDF 뷰어.

- 기획서: [docs/기획서.md](docs/기획서.md) — 기능·UX 결정의 기준. 기획과 다르게 구현해야 하면 먼저 사용자와 상의하고 기획서를 갱신한다.

## Git

Git 작업(브랜치, 커밋, 머지, 태그) 전에는 반드시 `git-workflow` 스킬([.claude/skills/git-workflow/SKILL.md](.claude/skills/git-workflow/SKILL.md))을 읽고 따른다. 규약은 스킬에서만 관리하고 여기에 중복해서 적지 않는다.

- 로컬 전용 저장소 (원격 없음, push 금지)
- `main`에 직접 커밋하지 않음, 머지는 사용자 확인 후

## 빌드

- Kotlin + Jetpack Compose, AGP 9 (Kotlin 내장), Gradle wrapper 사용
- JDK 17, Android SDK 경로는 `local.properties`(커밋 안 함)

```powershell
.\gradlew.bat assembleDebug      # 디버그 APK 빌드
.\gradlew.bat installDebug       # 연결된 기기/에뮬레이터에 설치
```

## 코드 구조

```
app/src/main/java/com/tabpdf/
  MainActivity.kt        진입점, 파일 열기
  pdf/                   PdfRenderer 래퍼 (렌더링 스레드 안전 처리)
  tabs/                  탭 목록 상태 (ViewModel)
  ui/                    화면 (뷰어, 탭 페이저, 상단 바)
```

## 구현 원칙

- 가볍게: 꼭 필요하지 않은 라이브러리는 추가하지 않는다. PDF 렌더링은 Android 기본 `PdfRenderer`.
- `PdfRenderer`는 스레드 안전하지 않다 — 문서마다 Mutex로 직렬화하고, 한 번에 한 페이지만 연다.
- 제스처 규칙은 기획서 5.2를 따른다 (폭 맞춤 상태의 좌우 스와이프 = 탭 전환, 확대 상태에서는 페이지 끝에 닿은 뒤 새로 민 경우에만 탭 전환).
