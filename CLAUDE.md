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

## 에뮬레이터 테스트

에뮬레이터의 시스템 파일 선택기는 adb 입력으로 파일을 고를 수 없으므로, 디버그 빌드 전용 훅으로 PDF를 연다.

```bash
# 1. 테스트 PDF를 앱 내부 저장소로 복사 (Git Bash; 파일마다)
#    새로 설치한 직후에는 files/ 폴더가 없으므로 먼저 만든다
adb shell run-as com.tabpdf mkdir -p files
adb exec-in run-as com.tabpdf sh -c "cat > files/Sample.pdf" < Sample.pdf
# 2. files/의 PDF를 모두 탭으로 열며 실행
adb shell am start -n com.tabpdf/.MainActivity --ez debug_open_test_files true
```

- 제스처는 `adb shell input swipe/tap`으로 확인한다. 핀치는 adb로 재현할 수 없으므로 실기기에서 확인.
- adb로 `/sdcard`에 push한 파일은 앱이 읽을 수 없다 (소유자가 shell).
- 세션 복원 확인: `adb shell am force-stop com.tabpdf` 후 훅 없이 `am start`로 다시 실행한다. 이전 세션은 DB에 남으므로, 처음부터 확인하려면 앱을 삭제 후 재설치.
- 버튼·메뉴는 좌표 대신 `uiautomator dump`로 텍스트의 bounds를 찾아 누른다 (화면 캡처는 축소본이라 좌표 환산이 틀리기 쉽다).
- 닫기 후 실행 취소 스낵바는 약 10초. 스크립트로 확인할 때는 닫기와 실행 취소를 한 번에 보낸다.
- Git Bash에서 adb 경로 인자(`/sdcard/...`)가 Windows 경로로 바뀌지 않게 `MSYS_NO_PATHCONV=1`을 설정한다.

## 코드 구조

```
app/src/main/java/com/tabpdf/
  TabPdfApp.kt           Application: DB 저장소, 순서 보장 DB 쓰기 큐
  MainActivity.kt        진입점, 파일 선택기, onStop 시 저장
  data/                  Room (그룹·탭·앱 상태), SessionRepository
  pdf/                   PdfRenderer 래퍼 (렌더링 스레드 안전 처리), 탭 전환 화면 썸네일
  tabs/                  탭·그룹 구조(Session.kt), ViewModel, 읽던 위치, 파일 접근 권한·복사본 관리
  ui/                    화면: 뷰어·스와이프 범위별 페이저·상단 바(TabsScreen), 탭 전환 화면·그룹 패널(TabSwitcher),
                         대화상자, 카드 목록 제스처(ItemGestures)
app/schemas/             Room 스키마 (DB 버전마다 커밋)
```

- DB 쓰기는 반드시 `TabPdfApp.enqueueWrite`로 한다 (순서 보장).
- 탭·그룹 구조를 바꾸는 ViewModel 동작은 새 `SessionState`를 만들어 `commit()`에 넘긴다. 저장은 전체 구조를 한 트랜잭션으로 맞추는 `saveStructure` 하나뿐이다 (부분 갱신 쿼리를 따로 만들지 않는다).
- DB 스키마를 바꾸면 `AppDatabase` 버전을 올리고 마이그레이션을 작성한다. 사용자 탭을 지우는 파괴적 마이그레이션은 쓰지 않는다.

## 구현 원칙

- 가볍게: 꼭 필요하지 않은 라이브러리는 추가하지 않는다. PDF 렌더링은 Android 기본 `PdfRenderer`.
- `PdfRenderer`는 스레드 안전하지 않다 — 문서마다 Mutex로 직렬화하고, 한 번에 한 페이지만 연다.
- 제스처 규칙은 기획서 5.2를 따른다 (폭 맞춤 상태의 좌우 스와이프 = 탭 전환, 확대 상태에서는 페이지 끝에 닿은 뒤 새로 민 경우에만 탭 전환).
