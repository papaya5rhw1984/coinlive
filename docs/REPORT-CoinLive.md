# REPORT — 코인 라이브 (RealUtil Lab / Company B) v1.0
- 경로: F:\AndroidStudioProjects\UsefulApps\CoinLive / com.ryu.coinlive / applicationId io.github.papaya5rhw1984.coinlive / 자체 88

## 채택안
암호화폐 실시간 시세 보드 — CoinGecko 무료·무키 simple/price 직접 호출. 기존 10종과 무중복(시세 신규 도메인).
- 회의: E1 코인 시세(채택) / E2 세계시계(보류: 라이브성 약) / E3 달위상(보류: 전부 오프라인) / E4 미니게임(보류: A 영역).

## 구현 (Kotlin+Compose/Material3, 외부 의존성 0)
코인 카탈로그 20종 중 관심코인 추가/삭제, KRW↔USD 전환, 24h 등락률 색(상승▲ 하락▼ 보합≈), 60초 자동 갱신+수동 새로고침, 카드 탭 클립보드 복사, 5테마, 시세/설정 2탭. HttpURLConnection(코루틴 IO)+org.json.

## 거버넌스
❌개인정보(type2 무수집, INTERNET만) ❌유료API(무키 공개 엔드포인트) ❌저작권(자체 텍스트/이모지). throttle 60초 2회(통화변경·코인추가는 예외)·로딩 중복차단·통화별 캐시·오프라인 폴백. **시세 표시 전용 — 투자 권유·매매·차트 추천 없음**, 설정에 출처·면책·무수집 고지.

## 검증(정직)
.kt 구문 스캔 통과(중괄호 141/141·괄호 416/416, 스코프함수 비한정, INTERNET 권한, apptemplate 잔존 0). curl은 샌드박스 네트워크 차단(HTTP 000)으로 실응답 미확인 → 파서는 CoinGecko 공개 스키마에 매핑. ⚠️ Android Studio assembleDebug+실기기 확인 필요.

## TODO
Ryu 런처 아이콘 교체.
