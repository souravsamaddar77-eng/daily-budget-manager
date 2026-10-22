package dev.daybudget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.daybudget.ui.WorkspaceViewModel
import dev.daybudget.ui.BudgetWorkspaceApp

class MainActivity : ComponentActivity() {
    private val model: WorkspaceViewModel by viewModels {
        val app = application as DayBudgetApplication
        WorkspaceViewModel.Factory(app.workspace, app.notifications)
    }
    private var receiverRegistered = false
    private val timeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { model.refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BudgetWorkspaceApp(model) }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeChanged, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        model.refresh()
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onStop() {
        if (receiverRegistered) unregisterReceiver(timeChanged)
        receiverRegistered = false
        super.onStop()
    }
}
