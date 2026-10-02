package vn.viettechtrans.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import vn.viettechtrans.app.ui.VttApp
import vn.viettechtrans.app.ui.theme.VttTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VttApplication).container
        setContent {
            VttTheme {
                VttApp(container)
            }
        }
    }
}
