package app.faxina.mac

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import java.awt.Dimension

fun main() {
    System.setProperty("apple.awt.application.name", "Faxina")
    application {
        val scope = rememberCoroutineScope()
        val controller = remember { MacController(scope) }
        val mail = remember { MailController(scope) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Faxina",
            state = rememberWindowState(size = DpSize(1180.dp, 800.dp)),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(980, 680) }
            FaxinaTheme { App(controller, mail) }
        }
    }
}
