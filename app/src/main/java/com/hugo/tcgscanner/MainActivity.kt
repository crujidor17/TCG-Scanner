package com.hugo.tcgscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

enum class Screen { SCAN, CART, COLLECTION, SETTINGS }

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val token by vm.token.collectAsState()
    var screen by remember { mutableStateOf(if (token.isBlank()) Screen.SETTINGS else Screen.SCAN) }
    BackHandler(enabled = screen != Screen.SCAN) { screen = Screen.SCAN }

    when (screen) {
        Screen.SCAN -> ScanScreen(
            vm,
            goCart = { screen = Screen.CART },
            goCollection = { screen = Screen.COLLECTION },
            goSettings = { screen = Screen.SETTINGS }
        )
        Screen.CART -> CartScreen(vm, back = { screen = Screen.SCAN }, toCollection = { screen = Screen.COLLECTION })
        Screen.COLLECTION -> CollectionScreen(vm, back = { screen = Screen.SCAN })
        Screen.SETTINGS -> SettingsScreen(vm, back = { screen = Screen.SCAN })
    }
}

// ---------------------------------------------------------------- Escáner

@Composable
fun ScanScreen(vm: AppViewModel, goCart: () -> Unit, goCollection: () -> Unit, goSettings: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    val cart by vm.cart.collectAsState()
    val last by vm.lastScan.collectAsState()
    val message by vm.message.collectAsState()
    val catalog by vm.catalog.collectAsState()
    var showSearch by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (hasPermission) {
            CameraView(onText = { vm.onFrameText(it) })
        } else {
            Text("Se necesita permiso de cámara para escanear", Modifier.align(Alignment.Center).padding(24.dp))
        }

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = goCollection) { Text("Colección") }
                FilledTonalButton(onClick = { showSearch = true }) { Text("Buscar") }
                FilledTonalButton(onClick = goSettings) { Text("Ajustes") }
            }
            if (catalog.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Primero descarga el catálogo de Riftbound en Ajustes.", Modifier.padding(12.dp))
                }
            }
            if (message.isNotBlank()) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(message, Modifier.padding(12.dp))
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) {
            last?.let {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it.blueprint.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${it.blueprint.expansion} · ${fmt(it.price)} · x${it.qty}")
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            Button(onClick = goCart, modifier = Modifier.fillMaxWidth()) {
                Text("Carrito (${cart.sumOf { it.qty }}) · ${fmt(total(cart))}")
            }
        }
    }

    if (showSearch) {
        SearchDialog(vm, onClose = { showSearch = false })
    }
}

@Composable
fun SearchDialog(vm: AppViewModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { vm.search(query) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        title = { Text("Añadir carta a mano") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Nombre de la carta") }, singleLine = true)
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(results, key = { it.id }) { bp ->
                        TextButton(onClick = { vm.addManual(bp); onClose() }) {
                            Text("${bp.displayName} · ${bp.expansion} ${bp.number}")
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun CameraView(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = OcrAnalyzer(executor) { currentOnText(it) }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, analyzer)
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analyzer.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

// ---------------------------------------------------------------- Carrito

@Composable
fun CartScreen(vm: AppViewModel, back: () -> Unit, toCollection: () -> Unit) {
    val cart by vm.cart.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            Text("Total: ${fmt(total(cart))}", style = MaterialTheme.typography.titleMedium)
        }
        Text("${cart.sumOf { it.qty }} cartas escaneadas", Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(cart, key = { it.blueprint.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardImage(item.blueprint.imageUrl)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(item.blueprint.displayName, style = MaterialTheme.typography.titleSmall)
                        Text("${item.blueprint.expansion} ${item.blueprint.number}")
                        Text("${fmt(item.price)} c/u")
                    }
                    TextButton(onClick = { vm.changeQty(item.blueprint.id, -1) }) { Text("−") }
                    Text("${item.qty}")
                    TextButton(onClick = { vm.changeQty(item.blueprint.id, 1) }) { Text("+") }
                    TextButton(onClick = { vm.removeFromCart(item.blueprint.id) }) { Text("✕") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.commitCart(); toCollection() }, enabled = cart.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Añadir a colección")
            }
            OutlinedButton(onClick = { vm.clearCart() }, enabled = cart.isNotEmpty()) { Text("Vaciar") }
        }
    }
}

@Composable
fun CardImage(url: String?) {
    if (url != null) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(width = 48.dp, height = 68.dp))
    } else {
        Spacer(Modifier.size(width = 48.dp, height = 68.dp))
    }
}

// ---------------------------------------------------------------- Colección

@Composable
fun CollectionScreen(vm: AppViewModel, back: () -> Unit) {
    val cards by vm.collection.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var toDelete by remember { mutableStateOf<CollectionCard?>(null) }
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            Text("${cards.sumOf { it.qty }} cartas", style = MaterialTheme.typography.titleMedium)
        }
        Text("Valor al escanear: ${fmt(totalCollection(cards, current = false))}")
        Text("Valor actual: ${fmt(totalCollection(cards, current = true))}")
        Button(onClick = { vm.refreshPrices() }, enabled = !busy && cards.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(if (busy) "Actualizando…" else "Actualizar precios")
        }
        if (message.isNotBlank()) Text(message, Modifier.padding(bottom = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(cards, key = { it.uid }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardImage(c.blueprint.imageUrl)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text("${c.blueprint.displayName} x${c.qty}", style = MaterialTheme.typography.titleSmall)
                        Text("${c.blueprint.expansion} ${c.blueprint.number}")
                        Text("Escaneada ${dateFmt.format(Date(c.scanDate))}: ${fmt(c.scanPrice)}")
                        Text("Ahora: ${fmt(c.currentPrice)}" + (c.updatedDate?.let { " (${dateFmt.format(Date(it))})" } ?: ""))
                    }
                    TextButton(onClick = { toDelete = c }) { Text("Quitar") }
                }
            }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Quitar de la colección?") },
            text = { Text("${c.blueprint.displayName} x${c.qty}") },
            confirmButton = { TextButton(onClick = { vm.removeFromCollection(c.uid); toDelete = null }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } }
        )
    }
}

// ---------------------------------------------------------------- Ajustes

@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit) {
    val savedToken by vm.token.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var tokenText by remember { mutableStateOf(savedToken) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back) { Text("← Volver") }
        Text("Ajustes", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = tokenText,
            onValueChange = { tokenText = it },
            label = { Text("Token de CardTrader (JWT)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { vm.saveToken(tokenText) }, enabled = tokenText.isNotBlank()) { Text("Guardar token") }
        Text("Catálogo de Riftbound: ${catalog.size} cartas")
        Button(onClick = { vm.downloadCatalog() }, enabled = !busy && savedToken.isNotBlank()) {
            Text(if (busy) "Descargando…" else "Descargar / actualizar catálogo")
        }
        if (message.isNotBlank()) Text(message)
    }
}
