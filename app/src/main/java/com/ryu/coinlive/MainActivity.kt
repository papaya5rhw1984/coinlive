package com.ryu.coinlive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ryu.coinlive.ui.AppTheme
import com.ryu.coinlive.ui.COIN_THEMES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("coinlive", Context.MODE_PRIVATE)
        setContent {
            var themeIdx by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
            AppTheme(themeIdx) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CoinApp(themeIdx) { themeIdx = it; prefs.edit().putInt("theme", it).apply() }
                }
            }
        }
    }
}

// ── 코인 카탈로그 (CoinGecko id → 표시명/심볼/이모지) ──
private data class Coin(val id: String, val name: String, val symbol: String, val emoji: String)

private val CATALOG = listOf(
    Coin("bitcoin", "비트코인", "BTC", "₿"),
    Coin("ethereum", "이더리움", "ETH", "Ξ"),
    Coin("ripple", "리플", "XRP", "✕"),
    Coin("solana", "솔라나", "SOL", "◎"),
    Coin("cardano", "카르다노", "ADA", "₳"),
    Coin("dogecoin", "도지코인", "DOGE", "Ð"),
    Coin("tron", "트론", "TRX", "▲"),
    Coin("polkadot", "폴카닷", "DOT", "●"),
    Coin("chainlink", "체인링크", "LINK", "⬡"),
    Coin("litecoin", "라이트코인", "LTC", "Ł"),
    Coin("avalanche-2", "아발란체", "AVAX", "🔺"),
    Coin("polygon-ecosystem-token", "폴리곤", "POL", "⬣"),
    Coin("stellar", "스텔라루멘", "XLM", "✦"),
    Coin("bitcoin-cash", "비트코인캐시", "BCH", "₿"),
    Coin("uniswap", "유니스왑", "UNI", "🦄"),
    Coin("cosmos", "코스모스", "ATOM", "⚛"),
    Coin("near", "니어프로토콜", "NEAR", "Ⓝ"),
    Coin("aptos", "앱토스", "APT", "🅰"),
    Coin("arbitrum", "아비트럼", "ARB", "🔵"),
    Coin("shiba-inu", "시바이누", "SHIB", "🐕")
)
private val CATALOG_MAP = CATALOG.associateBy { it.id }
private fun coinOf(id: String): Coin = CATALOG_MAP[id] ?: Coin(id, id, id.uppercase(), "🪙")

// 통화 단위
private data class Cur(val code: String, val label: String, val symbol: String)
private val CURRENCIES = listOf(Cur("krw", "원화", "₩"), Cur("usd", "달러", "$"))

private data class Quote(val price: Double, val change24h: Double)
private data class Snapshot(val quotes: Map<String, Quote>, val fetchedUnix: Long)

private fun fmtPrice(v: Double, code: String): String {
    val sym = CURRENCIES.first { it.code == code }.symbol
    val num = when {
        v >= 1000 -> String.format(Locale.US, "%,.0f", v)
        v >= 1 -> String.format(Locale.US, "%,.2f", v)
        else -> String.format(Locale.US, "%,.6f", v).trimEnd('0').trimEnd('.')
    }
    return "$sym$num"
}
private fun fmtTime(unix: Long): String =
    if (unix <= 0) "—" else SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(Date(unix * 1000))

@Composable
private fun CoinApp(themeIdx: Int, onTheme: (Int) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("coinlive", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // 호출 제한: 60초 슬라이딩 윈도우 내 최대 2회 (새로고침/복귀/예약 갱신 합산).
    // 통화 단위 변경(KRW↔USD)·관심코인 추가는 즉시 재호출이 필요하므로 옵션 변경으로 보고 예외 처리.
    val refreshTimes = remember { mutableListOf<Long>() }

    val watch = remember {
        mutableStateListOf<String>().also {
            val saved = (prefs.getString("watch", "bitcoin,ethereum,ripple,solana,dogecoin")
                ?: "").split(",").filter { s -> s.isNotBlank() && CATALOG_MAP.containsKey(s) }
            it.addAll(if (saved.isEmpty()) listOf("bitcoin", "ethereum") else saved)
        }
    }
    var quotes by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }
    var cur by remember { mutableStateOf(prefs.getString("cur", "krw") ?: "krw") }
    var live by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var fetchedUnix by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("불러오는 중…") }
    var tab by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }

    fun saveWatch() { prefs.edit().putString("watch", watch.joinToString(",")).apply() }
    fun cacheKey(c: String) = "cache_$c"

    fun loadCache(c: String) {
        prefs.getString(cacheKey(c), null)?.let { raw ->
            parseSnapshot(raw)?.let { s ->
                quotes = s.quotes; fetchedUnix = s.fetchedUnix
                note = "저장된 시세 (${fmtTime(s.fetchedUnix)})"
            }
        }
    }

    // bypassThrottle: 통화 변경/코인 추가 등 옵션 변경 시 true (제한 미적용·기록 안 함)
    fun refresh(silent: Boolean = false, bypassThrottle: Boolean = false) {
        if (loading) return
        if (watch.isEmpty()) { quotes = emptyMap(); return }
        val now = System.currentTimeMillis()
        if (!bypassThrottle) {
            refreshTimes.removeAll { now - it > 60_000 }
            if (refreshTimes.size >= 2) {
                if (!silent) {
                    val waitSec = ((60_000 - (now - refreshTimes.first())) / 1000) + 1
                    Toast.makeText(ctx, "잠시 후 다시 시도해주세요 (${waitSec}초)", Toast.LENGTH_SHORT).show()
                }
                return
            }
            refreshTimes.add(now)
        }
        loading = true
        val ids = watch.toList()
        val c = cur
        scope.launch {
            val raw = withContext(Dispatchers.IO) { fetchRaw(ids, c) }
            val res = raw?.let { parseQuotes(it, c) }
            if (res != null) {
                quotes = res; live = true
                fetchedUnix = System.currentTimeMillis() / 1000
                note = "실시간"
                prefs.edit().putString(cacheKey(c),
                    buildCacheJson(res, fetchedUnix)).apply()
                // 홈 위젯 캐시 갱신(BTC/ETH 보유 시) + fetch 없이 위젯 다시 그리기
                if (res.containsKey("bitcoin") || res.containsKey("ethereum")) {
                    val we = prefs.edit()
                    res["bitcoin"]?.let { we.putString("w_bitcoin_p", it.price.toString()).putString("w_bitcoin_c", it.change24h.toString()) }
                    res["ethereum"]?.let { we.putString("w_ethereum_p", it.price.toString()).putString("w_ethereum_c", it.change24h.toString()) }
                    we.putString("w_cur", c).putLong("w_time", System.currentTimeMillis()).apply()
                    CoinWidget.requestUpdate(ctx)
                }
            } else {
                live = false
                loadCache(c)
                if (quotes.isEmpty()) note = "오프라인 — 저장된 시세 없음"
                else note = "오프라인 — ${fmtTime(fetchedUnix)} 저장 시세"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadCache(cur)
        refresh(silent = true)
    }
    // 통화 단위 변경 → 옵션 변경(예외)으로 즉시 갱신, 캐시 먼저 표시
    // 최초 합성에서는 위 LaunchedEffect(Unit)이 이미 로드하므로 중복 호출 방지
    var firstCur by remember { mutableStateOf(true) }
    LaunchedEffect(cur) {
        if (firstCur) { firstCur = false; return@LaunchedEffect }
        loadCache(cur)
        refresh(silent = true, bypassThrottle = true)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val ageSec = System.currentTimeMillis() / 1000 - fetchedUnix
                if (fetchedUnix > 0 && ageSec > 60 && !loading) refresh(silent = true)
            }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // 자동 갱신: 60초마다 (throttle 안에서 안전 — 분당 1회)
    LaunchedEffect(watch.size) {
        while (true) {
            delay(60_000)
            if (!loading) refresh(silent = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("🪙 코인 라이브", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground)
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = { refresh() }) { Text("새로고침") }
        }
        Row(Modifier.padding(start = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape)
                .background(if (live) Color(0xFF2EB872) else Color(0xFFE0A53C)))
            Spacer(Modifier.width(6.dp))
            Text(note + (if (live) " · ${fmtTime(fetchedUnix)}" else ""),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("시세", "설정").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> PriceTab(
                watch = watch, quotes = quotes, cur = cur,
                onCur = { cur = it; prefs.edit().putString("cur", it).apply() },
                onAdd = { picker = true },
                onRemove = { id -> watch.remove(id); saveWatch() }
            )
            else -> SettingsTab(themeIdx, onTheme, live, fetchedUnix, watch.size)
        }
    }

    if (picker) {
        CoinPicker(
            available = CATALOG.filter { it.id !in watch },
            onDismiss = { picker = false }
        ) { id ->
            if (id !in watch) {
                watch.add(id); saveWatch()
                refresh(silent = true, bypassThrottle = true) // 코인 추가 = 옵션 변경(예외)
            }
            picker = false
        }
    }
}

@Composable
private fun PriceTab(
    watch: SnapshotStateList<String>, quotes: Map<String, Quote>, cur: String,
    onCur: (String) -> Unit, onAdd: () -> Unit, onRemove: (String) -> Unit
) {
    var sort by remember { mutableIntStateOf(0) } // 0 기본 · 1 상승순 · 2 하락순
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CURRENCIES.forEach { c ->
                val sel = cur == c.code
                FilterChip(
                    selected = sel, onClick = { if (!sel) onCur(c.code) },
                    label = { Text("${c.symbol} ${c.label}") },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = onAdd,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text("＋ 코인") }
        }
        // 정렬 칩
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            listOf("기본", "📈 상승", "📉 하락").forEachIndexed { i, label ->
                FilterChip(
                    selected = sort == i, onClick = { sort = i },
                    label = { Text(label, fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        if (watch.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("관심 코인이 없어요.\n‘＋ 코인’으로 추가해보세요.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val ordered = remember(watch.toList(), quotes, sort) {
                val base = watch.toList()
                when (sort) {
                    1 -> base.sortedByDescending { quotes[it]?.change24h ?: -1e9 }
                    2 -> base.sortedBy { quotes[it]?.change24h ?: 1e9 }
                    else -> base
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)) {
                items(ordered, key = { it }) { id ->
                    CoinRow(id, quotes[id], cur, onRemove)
                }
            }
        }
    }
}

private val COIN_UP = Color(0xFFE0574D)   // 상승(한국식 빨강)
private val COIN_DOWN = Color(0xFF3A7BD5) // 하락(파랑)
private val COIN_FLAT = Color(0xFF8A8F98)

@Composable
private fun CoinRow(id: String, q: Quote?, cur: String, onRemove: (String) -> Unit) {
    val ctx = LocalContext.current
    val coin = coinOf(id)

    // 가격이 바뀌면 행을 잠깐 상승/하락 색으로 플래시
    var prev by remember(id) { mutableStateOf<Double?>(null) }
    var dir by remember(id) { mutableIntStateOf(0) }
    val flash = remember(id) { Animatable(0f) }
    LaunchedEffect(q?.price) {
        val np = q?.price; val pp = prev
        if (pp != null && np != null && np != pp) {
            dir = if (np > pp) 1 else -1
            flash.snapTo(1f)
            flash.animateTo(0f, tween(900))
        }
        if (np != null) prev = np
    }
    val baseBg = MaterialTheme.colorScheme.surfaceVariant
    val rowBg = lerp(baseBg, if (dir >= 0) COIN_UP else COIN_DOWN, flash.value * 0.30f)

    val stripe = when {
        q == null -> COIN_FLAT
        q.change24h >= 0.005 -> COIN_UP
        q.change24h <= -0.005 -> COIN_DOWN
        else -> COIN_FLAT
    }

    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(14.dp))
            .background(rowBg)
            .clickable {
                if (q != null) {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("시세", "${coin.name} ${fmtPrice(q.price, cur)}"))
                    Toast.makeText(ctx, "복사됨: ${coin.name} ${fmtPrice(q.price, cur)}", Toast.LENGTH_SHORT).show()
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(stripe))
        Spacer(Modifier.width(11.dp))
        Box(Modifier.size(38.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Text(coin.emoji, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(coin.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(coin.symbol, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (q != null) fmtPrice(q.price, cur) else "—",
                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (q != null) {
                val (sym, col) = when {
                    q.change24h >= 0.005 -> "▲" to COIN_UP
                    q.change24h <= -0.005 -> "▼" to COIN_DOWN
                    else -> "≈" to COIN_FLAT
                }
                Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                    .background(col.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("$sym ${String.format(Locale.US, "%.2f", kotlin.math.abs(q.change24h))}%",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = col)
                }
            }
        }
        IconButton(onClick = { onRemove(id) }) {
            Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsTab(themeIdx: Int, onTheme: (Int) -> Unit, live: Boolean, fetchedUnix: Long, count: Int) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("🎨 디자인 테마", fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        COIN_THEMES.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .border(if (themeIdx == i) 2.dp else 1.dp,
                    if (themeIdx == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(14.dp))
                .clickable { onTheme(i) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(t.accent)))
                Spacer(Modifier.width(12.dp))
                Text("${t.emoji} ${t.name}", Modifier.weight(1f), fontSize = 16.sp,
                    fontWeight = if (themeIdx == i) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface)
                if (themeIdx == i) Text("✓", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("ℹ️ 시세 정보", fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            (if (live) "상태: 실시간 · 관심 ${count}개\n마지막 갱신: ${fmtTime(fetchedUnix)}"
            else "상태: 오프라인(저장 시세)") +
            "\n\n시세는 CoinGecko 무료 공개 API(api.coingecko.com)의 단순 가격 데이터이며 참고용입니다. " +
            "본 앱은 시세 표시 전용으로, 투자 권유·매매·거래 기능을 제공하지 않습니다. " +
            "개인정보를 수집하지 않으며, 시세 조회 외 네트워크 통신을 하지 않습니다. " +
            "갱신은 60초당 1회 자동·수동 합산 최대 2회로 제한됩니다.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoinPicker(available: List<Coin>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(q, available) {
        if (q.isBlank()) available
        else available.filter { it.name.contains(q, true) || it.symbol.contains(q, true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Column(Modifier.padding(16.dp)) {
                Text("코인 추가", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(q, { q = it }, label = { Text("검색 (이름/심볼)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("추가할 코인이 없어요", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(filtered) { coin ->
                            Row(Modifier.fillMaxWidth().clickable { onPick(coin.id) }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(coin.emoji, fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(coin.name, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(10.dp))
                                Text(coin.symbol, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

// ── 네트워크 ──
private fun fetchRaw(ids: List<String>, cur: String): String? {
    return try {
        val idParam = ids.joinToString(",")
        val url = URL("https://api.coingecko.com/api/v3/simple/price" +
            "?ids=$idParam&vs_currencies=$cur&include_24hr_change=true")
        val con = url.openConnection() as HttpURLConnection
        con.connectTimeout = 7000; con.readTimeout = 7000; con.requestMethod = "GET"
        con.setRequestProperty("Accept", "application/json")
        if (con.responseCode != 200) { con.disconnect(); return null }
        val text = con.inputStream.bufferedReader().use { it.readText() }
        con.disconnect()
        text
    } catch (e: Exception) { null }
}

private fun parseQuotes(text: String, cur: String): Map<String, Quote>? {
    return try {
        val obj = JSONObject(text)
        val out = HashMap<String, Quote>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val o = obj.optJSONObject(id) ?: continue
            val price = o.optDouble(cur, Double.NaN)
            if (price.isNaN()) continue
            val chg = o.optDouble("${cur}_24h_change", 0.0)
            out[id] = Quote(price, chg)
        }
        if (out.isEmpty()) null else out
    } catch (e: Exception) { null }
}

// 캐시 직렬화: simple/price 와 동일 구조로 저장(통화별)
private fun buildCacheJson(quotes: Map<String, Quote>, fetchedUnix: Long): String {
    val root = JSONObject()
    val data = JSONObject()
    for ((id, q) in quotes) {
        data.put(id, JSONObject().put("price", q.price).put("change", q.change24h))
    }
    root.put("fetchedUnix", fetchedUnix)
    root.put("data", data)
    return root.toString()
}

private fun parseSnapshot(text: String): Snapshot? {
    return try {
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        val out = HashMap<String, Quote>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val o = data.getJSONObject(id)
            out[id] = Quote(o.optDouble("price", 0.0), o.optDouble("change", 0.0))
        }
        Snapshot(out, root.optLong("fetchedUnix", 0L))
    } catch (e: Exception) { null }
}
