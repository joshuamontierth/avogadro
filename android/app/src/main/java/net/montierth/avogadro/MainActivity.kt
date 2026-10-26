package net.montierth.avogadro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import net.montierth.avogadro.ui.AvogadroApp
import net.montierth.avogadro.ui.AvogadroTheme
import net.montierth.avogadro.ui.SearchViewModel

class MainActivity : ComponentActivity() {
    private val vm: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { AvogadroTheme { AvogadroApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_FOCUS) vm.requestFocus()
    }

    companion object {
        /** Sent by the home-screen widget: open ready to type. */
        const val ACTION_FOCUS = "net.montierth.avogadro.FOCUS"
    }
}
