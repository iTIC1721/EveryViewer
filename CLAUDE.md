# EveryViewer (멀티 탭 문서 뷰어 — 옛 가칭 TabPDF)

PDF·텍스트·마크다운·이미지·오피스 문서를 탭으로 열어두고 좌우 스와이프로 넘기며, 탭 그룹으로 관리하고, 탭 위에 필기하는 Android 뷰어.

- 내부 식별자(`applicationId`, 패키지 `com.tabpdf`)는 기존 탭 데이터가 이어지도록 바꾸지 않는다.

- 기획서: [docs/기획서.md](docs/기획서.md) — 기능·UX 결정의 기준. 기획과 다르게 구현해야 하면 먼저 사용자와 상의하고 기획서를 갱신한다.

## Git

Git 작업(브랜치, 커밋, 머지, 태그) 전에는 반드시 `git-workflow` 스킬([.claude/skills/git-workflow/SKILL.md](.claude/skills/git-workflow/SKILL.md))을 읽고 따른다. 규약은 스킬에서만 관리하고 여기에 중복해서 적지 않는다.

- 원격 `origin` = GitHub 비공개 저장소 `iTIC1721/EveryViewer`. 올리는 것은 `main`과 태그, 강제 푸시 금지
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
# 1. 테스트 파일을 앱 내부 저장소로 복사 (Git Bash; 파일마다, 형식은 무엇이든)
#    새로 설치한 직후에는 files/ 폴더가 없으므로 먼저 만든다
adb shell run-as com.tabpdf mkdir -p files
adb exec-in run-as com.tabpdf sh -c "cat > files/Sample.pdf" < Sample.pdf
# 2. files/의 파일을 모두 탭으로 열며 실행
adb shell am start -n com.tabpdf/.MainActivity --ez debug_open_test_files true
```

- 제스처는 `adb shell input swipe/tap`으로 확인한다. 핀치는 adb로 재현할 수 없으므로 실기기에서 확인.
- adb로 `/sdcard`에 push한 파일은 앱이 읽을 수 없다 (소유자가 shell).
- 세션 복원 확인: `adb shell am force-stop com.tabpdf` 후 훅 없이 `am start`로 다시 실행한다. 이전 세션은 DB에 남으므로, 처음부터 확인하려면 앱을 삭제 후 재설치.
- 버튼·메뉴는 좌표 대신 `uiautomator dump`로 텍스트나 content-desc의 bounds를 찾아 누른다 (화면 캡처는 축소본이라 좌표 환산이 틀리기 쉽다). 아이콘 버튼에는 화면 낭독기용 설명을 꼭 단다 — 테스트에서도 그것으로 찾는다.
- 다른 앱에서 열기·공유 확인: 테스트 APK를 설치(`installDebugAndroidTest`)하면 그 안의 `TestPdfProvider`가 보내는 앱 역할을 한다.
  `adb shell am start -a android.intent.action.SEND -t application/pdf --eu android.intent.extra.STREAM content://com.tabpdf.test.pdfs/shared.pdf -n com.tabpdf/.MainActivity`
- 파일 없이 글만 공유하는 경우: `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'공유할 글'" -n com.tabpdf/.MainActivity`
- 테스트 파일은 경로가 짧은 곳(예: `%TEMP%\evtest`)에서 만든다. 세션 임시 폴더는 경로가 길어 Windows 프로그램(python 등)이 실행되지 않는다.
- 필기: `adb shell input stylus ...`는 펜(TOOL_TYPE_STYLUS)으로 인식되지 않는다. 펜·측면 버튼·여러 손가락 규칙은 계측 테스트 `InkInputTest`(UiAutomation으로 이벤트 주입)로 확인한다. 이 테스트는 에뮬레이터의 탭·필기·files/·설정을 지운다. 펜 지연·손바닥 무시·S펜 버튼은 실기기로 확인.
- docx·hwpx 조판 비교: 원본을 Word·한글로 PDF로 만든 뒤(이 PC의 Office·한글 COM 자동화), 같은 파일을 `files/render-in/`에 복사하고 `adb shell am instrument -w -e class com.tabpdf.doc.RenderDump com.tabpdf.test/androidx.test.runner.AndroidJUnitRunner`(앱이 지워지지 않게 단독 실행)로 `files/render-out/`에 쪽 PNG를 뽑아 나란히 본다. 한글의 PDF 내보내기는 전체가 약 16pt 어긋나므로 위치는 hwpx 안의 `Preview/PrvImage.png`로 확인한다. pptx는 PowerPoint COM의 `Slide.Export(경로, "PNG", 폭, 높이)`로 슬라이드마다 그림을 뽑아 비교한다. 사용자 견본 파일은 저장소에 넣지 않는다.
- 에뮬레이터는 두 개다: C 드라이브 SDK의 `Pixel_4_XL_API_30`(Android 11)과, C 드라이브 용량 때문에 D 드라이브에 둔 `Pixel_6_API_35`(Android 15; SDK `D:\Android\sdk`, AVD `D:\Android\avd`, `platform-tools`는 C SDK로 연결). Android 15 실행:
  `$env:ANDROID_AVD_HOME='D:\Android\avd'; $env:ANDROID_SDK_ROOT='D:\Android\sdk'; $env:ANDROID_HOME='D:\Android\sdk'; & D:\Android\sdk\emulator\emulator.exe -avd Pixel_6_API_35`
  (`ANDROID_HOME`도 지정해야 한다: 시스템에 C 드라이브 SDK로 잡혀 있으면 그쪽에서 시스템 이미지를 찾다 실패한다)
- 텍스트 검색·선택·PDF 링크: PDF에서는 Android 12+(SDK 확장 13)·15+에서만 동작하므로 Android 11 에뮬레이터에서는 결과가 없다. PDF는 Android 15 에뮬레이터로, 다른 형식은 계측 테스트 `SearchTest`·`PptxTest`로 확인한다. PDF 링크는 `PdfLinkTest`(테스트 assets의 `links.pdf`). adb `input text`로는 한글을 입력할 수 없다.
- adb `input` 명령은 하나에 수백 ms가 걸려 실제 손가락보다 훨씬 느리다. 끌기처럼 시간에 민감한 제스처는 한 번의 `adb shell "..."` 안에 이어서 보낸다.
- 닫기 후 실행 취소 스낵바는 약 10초. 스크립트로 확인할 때는 닫기와 실행 취소를 한 번에 보낸다.
- Git Bash에서 adb 경로 인자(`/sdcard/...`)가 Windows 경로로 바뀌지 않게 `MSYS_NO_PATHCONV=1`을 설정한다.

## 코드 구조

```
app/src/main/java/com/tabpdf/
  TabPdfApp.kt           Application: DB 저장소, 순서 보장 DB 쓰기 큐
  MainActivity.kt        진입점, 파일 선택기, onStop 시 저장
  data/                  Room (그룹·탭·앱 상태), SessionRepository
  doc/                   형식과 무관한 Document 인터페이스, 형식 판별(FileFormat), 형식별 여는 곳(Documents),
                         흐르는 문서 조판·페이지 나누기(Flow), 텍스트·마크다운·이미지 문서, 인코딩 판별, 썸네일.
                         텍스트는 크기 제한 없이 조각씩 읽고 뒤에서 이어서 조판(StreamedTextDocument: 쪽 목록이 자라는
                         문서 — Document.progress). 쪽 나눔은 TextDocument.layOut(통째 조판)과 같아야 한다(LargeTextTest)
  doc/office/            docx·hwpx·pptx: 안전한 zip·XML 읽기, 형식별 해석기(DocxReader, HwpxReader) → 공통 모델(OfficeModel)
                         → 용지 단위 조판(OfficeLayout: 문단·줄 간격·표 병합·테두리·쪽 넘김) → SheetDocument.
                         pptx: PptxReader(레이아웃·마스터 상속, 테마 색) → 슬라이드 모델(SlideModel) → SlideDocument
                         (도형·연결선·그림을 슬라이드 위치에 그림, 글상자·표는 OfficeLayout.box로 조판, TableStyles: 기본 표 스타일)
  pdf/                   PdfRenderer 래퍼 (렌더링 스레드 안전 처리)
  ink/                   필기: 도구·브러시·획 저장 형식(Ink), 탭별 필기와 되돌리기(InkStore),
                         입력 규칙과 그리는 중인 획 표시(InkInput: 창 위 InProgressStrokesView, 터치는 받지 않음)
  tabs/                  탭·그룹 구조(Session.kt), ViewModel, 읽던 위치, 파일 접근 권한·복사본 관리
  ui/                    화면: 뷰어(DocumentViewer)·스와이프 범위별 페이저·상단 바(TabsScreen), 탭 전환 화면·그룹 패널(TabSwitcher),
                         대화상자, 카드 목록 제스처(ItemGestures)
app/schemas/             Room 스키마 (DB 버전마다 커밋)
```

- DB 쓰기는 반드시 `TabPdfApp.enqueueWrite`로 한다 (순서 보장).
- 탭·그룹 구조를 바꾸는 ViewModel 동작은 새 `SessionState`를 만들어 `commit()`에 넘긴다. 저장은 전체 구조를 한 트랜잭션으로 맞추는 `saveStructure` 하나뿐이다 (부분 갱신 쿼리를 따로 만들지 않는다).
- DB 스키마를 바꾸면 `AppDatabase` 버전을 올리고 마이그레이션을 작성한다. 사용자 탭을 지우는 파괴적 마이그레이션은 쓰지 않는다.

## 구현 원칙

- UI는 글자보다 그림·아이콘·색으로 정보를 전한다. 버튼·도구 이름은 화면에 쓰지 않고 화면 낭독기용 설명(contentDescription)으로만 단다. 글자는 설정·오류 안내처럼 꼭 필요한 곳에만. 새 UI는 비슷한 앱들의 관례를 먼저 찾아 따른다.

- 라이브러리: 널리 쓰이는 것은 쓴다 (기획서 7장: 필기 Jetpack Ink, 마크다운 commonmark-java). 다만 기획서 8장의 용량·시작 시간 목표를 넘기지 않는지 확인한다. PDF 렌더링은 Android 기본 `PdfRenderer`, 오피스 형식은 직접 해석.
- 뷰어·썸네일·필기는 형식별 해석기가 만든 "문서"(페이지 수·크기·그리기)만 상대한다. 형식별 처리를 뷰어에 넣지 않는다. 글이 흐르는 형식은 고정 가상 폭으로 조판한다 (필기 좌표가 화면 폭과 무관하게 유지되도록, 기획서 5.7).
- 필기 좌표는 페이지 공간(폭 `INK_PAGE_WIDTH` = 1000)이다. Ink의 `CanvasStrokeRenderer.draw`에 넘기는 행렬은 품질 계산용일 뿐 캔버스에 적용되지 않는다 — 캔버스에 직접 `concat`한다 (`InkPainter`).
- 필기 입력은 Initial 단계에서 포인터를 소비하고, 다른 제스처 처리기는 `inkClaimed` 표시를 보고 빠진다 (목록이 세로 스크롤을 직접 소비하므로 "소비됨"만으로는 필기인지 알 수 없다).
- `PdfRenderer`는 스레드 안전하지 않다 — 문서마다 Mutex로 직렬화하고, 한 번에 한 페이지만 연다.
- 제스처 규칙은 기획서 5.2를 따른다 (폭 맞춤 상태의 좌우 스와이프 = 탭 전환, 확대 상태에서는 페이지 끝에 닿은 뒤 새로 민 경우에만 탭 전환).
