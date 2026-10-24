# EveryViewer (멀티 탭 문서 뷰어 — 옛 가칭 TabPDF)

PDF·텍스트·마크다운·이미지·오피스 문서를 탭으로 열어두고 좌우 스와이프로 넘기며, 탭 그룹으로 관리하고, 탭 위에 필기하는 Android 뷰어.

- 내부 식별자(`applicationId`, 패키지 `com.tabpdf`)는 기존 탭 데이터가 이어지도록 바꾸지 않는다.

- 기획서: [docs/기획서.md](docs/기획서.md) — 기능·UX 결정의 기준. 기획과 다르게 구현해야 하면 먼저 사용자와 상의하고 기획서를 갱신한다.

## Git

Git 작업(브랜치, 커밋, 머지, 태그) 전에는 반드시 `git-workflow` 스킬([.claude/skills/git-workflow/SKILL.md](.claude/skills/git-workflow/SKILL.md))을 읽고 따른다. 규약은 스킬에서만 관리하고 여기에 중복해서 적지 않는다.

- 로컬 전용 저장소 (원격 없음, push 금지)
- `main`에 직접 커밋하지 않음, 머지는 사용자 확인 후

## 빌드

- Kotlin + Jetpack Compose, AGP 9 (Kotlin 내장), Gradle wrapper 사용
- JDK 17, Android SDK 경로는 `local.properties`(커밋 안 함)

```powershell
.\gradlew.bat assembleDebug                 # 디버그 APK 빌드
.\gradlew.bat installDebug                  # 연결된 기기/에뮬레이터에 설치
.\gradlew.bat connectedDebugAndroidTest     # 계측 테스트 (기기/에뮬레이터 필요)
```

- 계측 테스트(`app/src/androidTest`)는 구조 편집 규칙, 저장·불러오기, DB 마이그레이션, 파일 복사를 다룬다. 구조 규칙(`SessionEdits.kt`)이나 DB를 바꾸면 반드시 돌린다.
- `connectedDebugAndroidTest`는 끝나면 앱을 지운다 → 에뮬레이터의 탭 데이터도 사라진다.
- 테스트 APK의 컴포넌트(예: `TestPdfProvider`)는 별도 프로세스에서 돌고 Kotlin 런타임이 없다. 그런 클래스는 Java로 쓴다.
- 시작 시간·용량은 릴리스 빌드로 잰다. 서명 설정을 커밋하지 않고 디버그 키로 서명하려면:
  `.\gradlew.bat assembleRelease -Pandroid.injected.signing.store.file=$env:USERPROFILE\.android\debug.keystore -Pandroid.injected.signing.store.password=android -Pandroid.injected.signing.key.alias=androiddebugkey -Pandroid.injected.signing.key.password=android`

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
- 버튼·메뉴는 좌표 대신 `uiautomator dump`로 텍스트나 content-desc의 bounds를 찾아 누른다 (화면 캡처는 축소본이라 좌표 환산이 틀리기 쉽다). 아이콘 버튼에는 화면 낭독기용 설명을 꼭 단다 — 테스트에서도 그것으로 찾는다.
- 다른 앱에서 열기·공유 확인: 테스트 APK를 설치(`installDebugAndroidTest`)하면 그 안의 `TestPdfProvider`가 보내는 앱 역할을 한다.
  `adb shell am start -a android.intent.action.SEND -t application/pdf --eu android.intent.extra.STREAM content://com.tabpdf.test.pdfs/shared.pdf -n com.tabpdf/.MainActivity`
- adb `input` 명령은 하나에 수백 ms가 걸려 실제 손가락보다 훨씬 느리다. 끌기처럼 시간에 민감한 제스처는 한 번의 `adb shell "..."` 안에 이어서 보낸다.
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

- 라이브러리: 널리 쓰이는 것은 쓴다 (기획서 7장: 필기 Jetpack Ink, 마크다운 commonmark-java). 다만 기획서 8장의 용량·시작 시간 목표를 넘기지 않는지 확인한다. PDF 렌더링은 Android 기본 `PdfRenderer`, 오피스 형식은 직접 해석.
- 뷰어·썸네일·필기는 형식별 해석기가 만든 "문서"(페이지 수·크기·그리기)만 상대한다. 형식별 처리를 뷰어에 넣지 않는다. 글이 흐르는 형식은 고정 가상 폭으로 조판한다 (필기 좌표가 화면 폭과 무관하게 유지되도록, 기획서 5.7).
- `PdfRenderer`는 스레드 안전하지 않다 — 문서마다 Mutex로 직렬화하고, 한 번에 한 페이지만 연다.
- 제스처 규칙은 기획서 5.2를 따른다 (폭 맞춤 상태의 좌우 스와이프 = 탭 전환, 확대 상태에서는 페이지 끝에 닿은 뒤 새로 민 경우에만 탭 전환).
