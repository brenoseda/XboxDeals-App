package com.xboxdeals

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

data class Game(
    val id: String,
    val title: String,
    val price: Double = 0.0,
    val msrp: Double = 0.0,
    val cur: String = "BRL",
    val alert: Boolean = true,
    val last: Double = -1.0 // último preço promocional já notificado
) {
    val onSale get() = msrp > 0 && price < msrp
    val discount get() = if (onSale) ((1 - price / msrp) * 100).toInt() else 0
}

/** Lista de jogos monitorados, guardada em SharedPreferences. */
object Repo {
    private fun p(c: Context) = c.getSharedPreferences("xboxdeals", Context.MODE_PRIVATE)

    fun live(c: Context) = p(c).getBoolean("live", false)
    fun setLive(c: Context, on: Boolean) = p(c).edit().putBoolean("live", on).apply()

    @Synchronized
    fun load(c: Context): List<Game> {
        val a = JSONArray(p(c).getString("games", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Game(
                o.getString("id"), o.getString("title"),
                o.optDouble("price", 0.0), o.optDouble("msrp", 0.0),
                o.optString("cur", "BRL"), o.optBoolean("alert", true), o.optDouble("last", -1.0)
            )
        }
    }

    @Synchronized
    fun edit(c: Context, f: (List<Game>) -> List<Game>) {
        val a = JSONArray()
        f(load(c)).forEach { g ->
            a.put(
                JSONObject().put("id", g.id).put("title", g.title).put("price", g.price)
                    .put("msrp", g.msrp).put("cur", g.cur).put("alert", g.alert).put("last", g.last)
            )
        }
        p(c).edit().putString("games", a.toString()).apply()
    }
}

/** Catálogo público da Microsoft Store (não oficial). Região BR / pt-BR. */
object Xbox {
    private const val BASE = "https://displaycatalog.mp.microsoft.com/v7.0"
    private const val LOC = "languages=pt-BR&market=BR"

    // ID de produto da loja: 12 caracteres alfanuméricos com ao menos um dígito (ex.: 9NBLGGH4R315)
    val ID = Regex("(?i)\\b(?=[a-z0-9]*\\d)[a-z0-9]{12}\\b")

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Busca por nome → lista de (id, título). */
    fun search(q: String): List<Pair<String, String>> {
        val url = "$BASE/productFamilies/autosuggest?$LOC&platformdependencyname=windows.xbox" +
            "&productFamilyNames=Games&query=${URLEncoder.encode(q, "UTF-8")}&topProducts=8"
        val rs = get(url).optJSONArray("Results") ?: return emptyList()
        val out = linkedMapOf<String, String>()
        for (i in 0 until rs.length()) {
            val ss = rs.getJSONObject(i).optJSONArray("Suggests") ?: continue
            for (j in 0 until ss.length()) {
                val s = ss.getJSONObject(j)
                val ms = s.optJSONArray("Metas") ?: continue
                for (k in 0 until ms.length()) {
                    val v = ms.getJSONObject(k).optString("Value")
                    if (ID.matches(v)) {
                        out[v.uppercase()] = s.optString("Title")
                        break
                    }
                }
            }
        }
        return out.map { it.key to it.value }
    }

    /** Preço atual e preço cheio (MSRP) de cada produto. */
    fun prices(ids: List<String>): List<Game> {
        if (ids.isEmpty()) return emptyList()
        val ps = get("$BASE/products?actionFilter=Browse&fieldsTemplate=details&$LOC&bigIds=${ids.joinToString(",")}")
            .optJSONArray("Products") ?: return emptyList()
        return List(ps.length()) { ps.getJSONObject(it) }.mapNotNull { p ->
            val title = p.optJSONArray("LocalizedProperties")?.optJSONObject(0)?.optString("ProductTitle")
            if (title.isNullOrEmpty()) return@mapNotNull null
            var price: JSONObject? = null
            val skus = p.optJSONArray("DisplaySkuAvailabilities")
            outer@ for (i in 0 until (skus?.length() ?: 0)) {
                val s = skus!!.getJSONObject(i)
                if (s.optJSONObject("Sku")?.optJSONObject("Properties")?.optBoolean("IsTrial") == true) continue
                val av = s.optJSONArray("Availabilities") ?: continue
                for (a in 0 until av.length()) {
                    val o = av.getJSONObject(a)
                    val pr = o.optJSONObject("OrderManagementData")?.optJSONObject("Price") ?: continue
                    if (o.optJSONArray("Actions")?.toString()?.contains("Purchase") == true) {
                        price = pr
                        break@outer
                    }
                }
            }
            Game(
                p.optString("ProductId").uppercase(), title,
                price?.optDouble("ListPrice", 0.0) ?: 0.0,
                price?.optDouble("MSRP", 0.0) ?: 0.0,
                price?.optString("CurrencyCode", "BRL") ?: "BRL"
            )
        }
    }
}

object Deals {
    fun money(g: Game, v: Double): String {
        val f = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
        try { f.currency = Currency.getInstance(g.cur) } catch (e: Exception) { }
        return f.format(v)
    }

    /** Consulta os preços e notifica os jogos com alerta ligado que estão em promoção. */
    fun check(c: Context) {
        val fresh = Xbox.prices(Repo.load(c).map { it.id }).associateBy { it.id }
        Repo.edit(c) { cur ->
            cur.map { g ->
                val f = fresh[g.id] ?: return@map g
                var last = g.last
                if (f.onSale) {
                    if (g.alert && f.price != last && notify(c, f)) last = f.price
                } else {
                    last = -1.0
                }
                g.copy(price = f.price, msrp = f.msrp, cur = f.cur, last = last)
            }
        }
    }

    private fun notify(c: Context, g: Game): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(
            NotificationChannel("deals", "Promoções", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.microsoft.com/store/productId/${g.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, "deals")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${g.title} em promoção!")
            .setContentText("${money(g, g.price)} (de ${money(g, g.msrp)}, -${g.discount}%)")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(g.id.hashCode(), n)
        return true
    }
}

class PriceWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Deals.check(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
