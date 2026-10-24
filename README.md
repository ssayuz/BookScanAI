# 📚 AI 북스캔 (Android)

책을 스마트폰으로 찍으면 **휨·구부림 펴기 → 구김·그림자 제거 → 필기 제거 → PDF 저장**까지 해 주는 북스캐너 앱입니다.

## 기능

| 기능 | 방식 |
|---|---|
| AI 자동 촬영·페이지 검출 | Google ML Kit 문서 스캐너 (자동 테두리 검출, 자동 촬영, 손가락·얼룩 AI 제거 필터) |
| 휨·구부림 보정 | 글줄을 찾아 각 줄의 곡선을 3차 곡선으로 근사 → 곧게 펴는 변위 맵 생성 → 책 안쪽 눌린 글자도 가로로 펴 줌 |
| 구김·그림자·책 가운데 어두움 제거 | 종이 배경(조명)을 추정해 나눠서 하얀 종이로 평탄화 |
| 필기 제거 | 컬러 펜·형광펜 제거, 밑줄 제거(검정·연필 포함), 본문 밖 여백 메모 제거(선택) |
| 양면 분리 | 펼친 두 쪽을 책 가운데 골을 찾아 좌/우 두 페이지로 |
| 갤러리 사진 불러오기 | 페이지 영역 자동 검출 + 원근 보정 |
| PDF 저장 | 원하는 위치(다운로드, 구글 드라이브 등)에 저장 |
| 기타 | 길게 눌러 끌어서 순서 변경, 탭하여 원본/보정 비교, 출력 컬러/회색조/선명한 흑백 |

## APK 만들기 — 방법 1: GitHub (PC에 아무것도 설치 안 해도 됨)

1. https://github.com 가입 → 오른쪽 위 **+ → New repository** → 이름 `BookScanAI` → Create.
2. 새 저장소 화면에서 **uploading an existing file** 클릭 → 압축을 푼 `BookScanAI` 폴더 **안의 모든 파일/폴더**를 끌어다 놓기 → Commit.
   - `.github` 폴더가 숨김 폴더라 안 올라가는 경우: 저장소에서 **Add file → Create new file** → 이름에 `.github/workflows/build-apk.yml` 입력 → 이 프로젝트의 같은 파일 내용을 붙여넣고 Commit.
3. 저장소 상단 **Actions** 탭 → `Build APK` 작업이 자동 실행됩니다 (약 5~8분). 안 보이면 **Build APK → Run workflow**.
4. 완료(초록 체크)된 작업을 눌러 아래쪽 **Artifacts → AI-BookScan-apk** 다운로드 → 압축 풀면 `AI-BookScan.apk`.
5. 휴대폰으로 옮겨 설치 (설정에서 *출처를 알 수 없는 앱 설치* 허용).

## APK 만들기 — 방법 2: Android Studio

1. Android Studio 설치 → **Open** → `BookScanAI` 폴더 선택 → Gradle 동기화 대기.
2. 메뉴 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. `app/build/outputs/apk/debug/app-debug.apk` 생성.

## 사용법

1. **📷 책 촬영 스캔**: 자동으로 페이지 테두리를 잡아 촬영합니다. 여러 쪽을 연속 촬영 후 저장.
   (Google Play 서비스가 없는 기기는 **🖼 사진 불러오기** 사용)
2. **⚙ 보정 설정**에서 원하는 보정을 켜고 끕니다.
3. **✨ AI 보정 실행** → 결과 썸네일을 탭해서 원본과 비교.
4. **📄 PDF 저장**.

### 잘 찍는 요령
- 책을 어두운 바탕 위에 놓고, 위에서 수직으로 찍으면 페이지 검출과 휨 보정이 가장 잘 됩니다.
- 그늘 없는 밝은 곳, 플래시는 끄기(반사광 방지).
- 양면을 한 번에 찍었다면 설정에서 **양면 분리**를 켜세요.

## 한계
- 컬러 필기 제거는 컬러 삽화도 지울 수 있습니다 (컬러 그림이 많은 책은 끄기).
- 글자 위를 덮은 검정 펜·연필 필기는 인쇄 글자와 구별이 어려워 완전히 지워지지 않습니다 (밑줄·여백 메모는 제거).
- 휨 보정은 글줄을 기준으로 하므로 그림만 있는 페이지는 보정되지 않습니다.

## 구성
```
app/src/main/java/com/bookscan/ai/
  MainActivity.kt   화면·흐름 (스캔, 불러오기, 보정, PDF)
  BookProcessor.kt  보정 엔진 (OpenCV): 페이지검출, 양면분리, 조명평탄화, 필기제거, 휨보정
  PdfExporter.kt    PDF 생성
  PageStore.kt      페이지 목록 저장
  ImageIO.kt        이미지 읽기/쓰기 (EXIF 회전 처리)
```
라이브러리: OpenCV 4.10, Google ML Kit Document Scanner, AndroidX/Material3. 최소 Android 8.0.
