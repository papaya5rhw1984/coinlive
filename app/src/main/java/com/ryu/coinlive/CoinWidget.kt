package com.ryu.coinlive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 홈 화면 코인 위젯 — 비트코인 · 이더리움 시세.
 * - 저장된 값으로 즉시 렌더(빈 화면 방지) → goAsync 백그라운드로 CoinGecko 새 fetch → 저장·재렌더.
 * - 통화(원/달러)는 앱 설정(prefs "cur")을 따라감. 탭하면 앱 열기.
 * - 자체 갱신 주기 1시간(updatePeriodMillis). 앱이 새로고침하면 render-only 로 위젯도 갱신.
 */
class CoinWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.ryu.coinlive.WIDGET_REFRESH"
        const val ACTION_RENDER = "com.ryu.coinlive.WIDGET_RENDER"
        private const val PREFS = "coinlive"

        /** 앱이 새 시세를 받은 뒤 위젯을 fetch 없이 다시 그릴 때(중복 호출 방지). */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CoinWidget::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, CoinWidget::class.java).apply {
                action = ACTION_RENDER
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, CoinWidget::class.java))
        if (ids.isEmpty()) return

        when (action) {
            ACTION_RENDER -> for (id in ids) renderWidget(context, mgr, id)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_REFRESH -> {
                for (id in ids) renderWidget(context, mgr, id)   // 캐시로 즉시
                val pending = goAsync()
                Thread {
                    try {
                        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        val cur = prefs.getString("cur", "krw") ?: "krw"
                        val raw = httpGet(
                            "https://api.coingecko.com/api/v3/simple/price" +
                                "?ids=bitcoin,ethereum&vs_currencies=$cur&include_24hr_change=true"
                        )
                        if (raw != null) {
                            val o = JSONObject(raw)
                            val e = prefs.edit()
                            for (cid in listOf("bitcoin", "ethereum")) {
                                val co = o.optJSONObject(cid) ?: continue
                                val p = co.optDouble(cur, Double.NaN)
                                if (p.isNaN()) continue
                                val c = co.optDouble("${cur}_24h_change", 0.0)
                                e.putString("w_${cid}_p", p.toString())
                                e.putString("w_${cid}_c", c.toString())
                            }
                            e.putString("w_cur", cur)
                            e.putLong("w_time", System.currentTimeMillis())
                            e.apply()
                            for (id in ids) renderWidget(context, mgr, id)
                        }
                    } catch (e: Exception) {
                        // 네트워크 실패 — 이미 캐시로 렌더돼 있으므로 무시
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, mgr, id)
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = prefs.getString("w_cur", prefs.getString("cur", "krw")) ?: "krw"
        val views = RemoteViews(context.packageName, R.layout.widget_coin)

        renderRow(prefs, views, "bitcoin", cur, R.id.w_btc_price, R.id.w_btc_chg)
        renderRow(prefs, views, "ethereum", cur, R.id.w_eth_price, R.id.w_eth_chg)

        val t = prefs.getLong("w_time", 0L)
        views.setTextViewText(R.id.w_time, if (t <= 0L) "탭하여 불러오기" else fmtTime(t))

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        mgr.updateAppWidget(id, views)
    }

    private fun renderRow(
        prefs: android.content.SharedPreferences, views: RemoteViews,
        cid: String, cur: String, priceId: Int, chgId: Int
    ) {
        val p = prefs.getString("w_${cid}_p", null)?.toDoubleOrNull()
        if (p == null) {
            views.setTextViewText(priceId, "—")
            views.setTextViewText(chgId, "")
            return
        }
        views.setTextViewText(priceId, fmtPrice(p, cur))
        val c = prefs.getString("w_${cid}_c", null)?.toDoubleOrNull()
        if (c == null) {
            views.setTextViewText(chgId, "")
        } else {
            val sym = when {
                c >= 0.005 -> "▲"
                c <= -0.005 -> "▼"
                else -> "≈"
            }
            views.setTextViewText(chgId, "$sym ${String.format(Locale.US, "%.2f", Math.abs(c))}%")
            val col = when {
                c >= 0.005 -> 0xFFE0574D.toInt()   // 상승 빨강(한국식)
                c <= -0.005 -> 0xFF3A7BD5.toInt()  // 하락 파랑
                else -> 0xFF9AA0AA.toInt()
            }
            views.setTextColor(chgId, col)
        }
    }

    private fun fmtPrice(v: Double, cur: String): String {
        val sym = if (cur == "krw") "₩" else "$"
        val num = when {
            v >= 1000 -> String.format(Locale.US, "%,.0f", v)
            v >= 1 -> String.format(Locale.US, "%,.2f", v)
            else -> String.format(Locale.US, "%,.4f", v)
        }
        return "$sym$num"
    }

    private fun fmtTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", Locale.KOREA).format(java.util.Date(ms))

    private fun httpGet(url: String): String? {
        return try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 7000
            con.readTimeout = 7000
            con.setRequestProperty("Accept", "application/json")
            if (con.responseCode != 200) { con.disconnect(); return null }
            val text = con.inputStream.bufferedReader().use { it.readText() }
            con.disconnect()
            text
        } catch (e: Exception) { null }
    }
}
