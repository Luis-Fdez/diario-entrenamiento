package com.luis.diario

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luis.diario.ui.BusyDialog
import com.luis.diario.ui.CalendarScreen
import com.luis.diario.ui.DiarioTheme
import com.luis.diario.ui.DiaryViewModel
import com.luis.diario.ui.EditorScreen
import com.luis.diario.ui.ImportDialogs
import com.luis.diario.ui.MessageDialog
import com.luis.diario.ui.Paper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Barras del sistema claras siempre (la app no tiene modo oscuro).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            DiarioTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(vm: DiaryViewModel = viewModel()) {
    val s = vm.state
    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setRoot)
    }

    // Guarda al salir de la app.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.saveNow() }

    when {
        s.rootUri == null -> SetupScreen(onPick = { pickRoot.launch(null) })
        vm.editor != null -> EditorScreen(vm)
        else -> CalendarScreen(vm, onChangeFolder = { pickRoot.launch(null) })
    }

    s.import?.let { ImportDialogs(vm, it) }
    s.busy?.let { BusyDialog(it) }
    s.message?.let { MessageDialog(it, vm::clearMessage) }
}

@Composable
private fun SetupScreen(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("Diario de entrenamiento", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Elige (o crea) una carpeta donde guardar el diario. Todo se queda en el móvil, en archivos que puedes " +
                "copiar o sincronizar. Si desinstalas la app, la carpeta no se borra.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Sugerencia: crea una carpeta «Entrenamiento» en Documentos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPick) { Text("Elegir carpeta") }
    }
}
