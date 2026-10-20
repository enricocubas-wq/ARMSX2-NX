package app.faxina

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.faxina.ui.FaxinaRoot
import app.faxina.ui.FaxinaTheme
import app.faxina.vm.MailViewModel
import app.faxina.vm.MainViewModel

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val mail: MailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FaxinaTheme {
                FaxinaRoot(main, mail)
            }
        }
    }
}
