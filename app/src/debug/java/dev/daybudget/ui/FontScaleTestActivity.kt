package dev.daybudget.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** An unexported debug test host. It never opens the application's database. */
class FontScaleTestActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Apply only the font override, before Activity resources or dialog windows exist.
        // The base resources and system configuration remain untouched between tests.
        val override = Configuration().apply { fontScale = launchFontScale }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    companion object {
        @Volatile var launchFontScale: Float = 1f
    }
}
