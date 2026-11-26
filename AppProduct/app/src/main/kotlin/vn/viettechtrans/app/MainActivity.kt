package vn.viettechtrans.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import vn.viettechtrans.app.ui.VttApp
import vn.viettechtrans.app.ui.theme.VttTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as VttApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (rotation) must not translate the same shared text again.
        if (savedInstanceState == null) handle(intent)
        setContent {
            VttTheme {
                VttApp(container)
            }
        }
    }

    /** singleTop: text or a picture shared while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        Incoming.from(intent)?.let(container.incoming::post)
    }
}
