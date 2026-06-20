# Ryu Android App Template (네이티브 출시 템플릿)

새 안드로이드 앱을 **처음부터 출시 준비된 상태**로 시작하는 베이스.
(Kotlin + Jetpack Compose · Material3 · 서명설정 · Ryu 배지 아이콘 · 스토어 자료 생성기)

## 새 앱 시작하는 법 (복사 → 이름 변경 → 빌드)

1. 이 `AppTemplate` 폴더를 통째로 복사해서 `F:\AndroidStudioProjects\새앱이름` 으로 둔다. (경로는 반드시 영문)
2. Android Studio에서 그 폴더를 연다.
3. 아래 5곳만 바꾼다:
   - `settings.gradle.kts` → `rootProject.name = "새앱이름"`
   - `app/build.gradle.kts` → `applicationId = "io.github.papaya5rhw1984.새앱"` (출시 후 변경 불가, 고유하게)
   - `app/src/main/res/values/strings.xml` → `app_name` = 보여줄 앱 이름
   - 코드 패키지(namespace)는 `com.ryu.apptemplate` 그대로 둬도 되고, 바꾸려면 Refactor▸Rename
4. (선택) `tools/base_icon.svg` 를 앱 고유 아이콘으로 교체 → `python tools/store_assets.py icon`
5. Gradle Sync → ▶ Run

## 출시 (서명 빌드)
- 자세한 절차는 `출시가이드.md` 참고.
- 키스토어 만들고 `keystore.properties` 채우면 release 자동 서명, 아니면 Build▸Generate Signed App Bundle 사용.

## 스토어 자료 생성기 (tools/store_assets.py)
```
pip install cairosvg pillow --break-system-packages
python tools/store_assets.py icon                      # 아이콘(런처 PNG + 512)
python tools/store_assets.py banner --title "My App" --sub "한 줄 소개"
python tools/store_assets.py shots ./폰캡처폴더          # 731x1300 스크린샷
```
결과물은 `tools/store_out/` 에 생성됨.

## 구조
```
app/src/main/
├─ AndroidManifest.xml
├─ java/com/ryu/apptemplate/
│  ├─ MainActivity.kt        시작 화면
│  └─ ui/Theme.kt            테마(브랜드 색)
└─ res/                      문자열·테마·아이콘
tools/
├─ base_icon.svg             앱 기본 아이콘 아트(교체용)
└─ store_assets.py           아이콘·배너·스크린샷 생성기
```
