package com.xboxdeals

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val visible = mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        visible.value = true
        // Retoma o monitoramento ao vivo (o Android 15 limita cada execução em segundo plano).
        if (Repo.live(this)) startForegroundService(Intent(this, LiveService::class.java))
    }

    override fun onStop() {
        visible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Verificação básica em segundo plano a cada 15 min (mínimo do Android).
        val req = PeriodicWorkRequestBuilder<PriceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("deals", ExistingPeriodicWorkPolicy.UPDATE, req)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF52B043))) {
                Surface(Modifier.fillMaxSize()) { Screen(visible.value) }
            }
        }
    }
}

@Composable
fun Screen(visible: Boolean) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(Repo.load(ctx)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var msg by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    var updated by remember { mutableStateOf("") }
    var offline by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(Repo.live(ctx)) }

    suspend fun doRefresh() {
        offline = try {
            withContext(Dispatchers.IO) { Deals.check(ctx) }
            updated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            false
        } catch (e: Exception) {
            true
        }
        games = Repo.load(ctx)
    }

    fun refresh() = scope.launch { doRefresh() }

    fun add(id: String) = scope.launch {
        msg = "Adicionando…"
        val g = try {
            withContext(Dispatchers.IO) {
                Xbox.prices(listOf(id)).firstOrNull()?.let { it.copy(last = if (it.onSale) it.price else -1.0) }
            }
        } catch (e: Exception) {
            null
        }
        if (g == null) { msg = "Jogo não encontrado."; return@launch }
        Repo.edit(ctx) { l -> if (l.any { it.id == g.id }) l else l + g }
        games = Repo.load(ctx); results = emptyList(); query = ""; msg = ""
    }

    fun search() = scope.launch {
        val m = Xbox.ID.find(query) // link ou ID colado
        if (m != null) { add(m.value.uppercase()); return@launch }
        msg = "Buscando…"
        results = try {
            withContext(Dispatchers.IO) { Xbox.search(query.trim()) }
        } catch (e: Exception) {
            emptyList()
        }
        msg = if (results.isEmpty()) "Nada encontrado." else ""
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Tempo real com o app aberto: consulta a loja a cada 30 s.
    LaunchedEffect(visible) {
        while (visible) {
            doRefresh()
            delay(30_000)
        }
    }

    Column(Modifier.systemBarsPadding().padding(16.dp)) {
        Text("Xbox Deals", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    offline -> "● Sem conexão com a loja"
                    updated.isEmpty() -> "● Conectando…"
                    else -> "● Ao vivo · atualizado às $updated"
                },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = { refresh() }) { Text("Atualizar") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monitorar com o app fechado")
                Text("Verifica a cada 1 min · usa mais bateria", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = live, onCheckedChange = { on ->
                live = on
                Repo.setLive(ctx, on)
                val i = Intent(ctx, LiveService::class.java)
                if (on) ctx.startForegroundService(i) else ctx.stopService(i)
            })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                singleLine = true, placeholder = { Text("Nome do jogo ou link da loja") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { search() }, enabled = query.isNotBlank()) { Text("Buscar") }
        }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(results) { (id, title) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f))
                    TextButton(onClick = { add(id) }) { Text("Adicionar") }
                }
            }
            if (results.isNotEmpty()) item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { if (games.isEmpty()) Text("Busque um jogo e adicione à sua lista.", Modifier.padding(top = 16.dp)) }
            items(games.sortedByDescending { it.onSale }) { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                g.onSale -> "${Deals.money(g, g.price)} (de ${Deals.money(g, g.msrp)}, -${g.discount}%)"
                                g.msrp > 0 -> Deals.money(g, g.price)
                                else -> "Sem preço no momento"
                            },
                            color = if (g.onSale) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Alerta", style = MaterialTheme.typography.labelSmall)
                        Switch(checked = g.alert, onCheckedChange = { on ->
                            Repo.edit(ctx) { l -> l.map { if (it.id == g.id) it.copy(alert = on) else it } }
                            games = Repo.load(ctx)
                        })
                    }
                    TextButton(onClick = {
                        Repo.edit(ctx) { l -> l.filter { it.id != g.id } }
                        games = Repo.load(ctx)
                    }) { Text("✕") }
                }
            }
        }
    }
}
