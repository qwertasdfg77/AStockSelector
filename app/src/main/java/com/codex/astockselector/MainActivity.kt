package com.codex.astockselector

import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.codex.astockselector.ui.AStockSelectorApp
import com.codex.astockselector.ui.AStockSelectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            AStockSelectorTheme {
                AStockSelectorApp()
            }
        }
    }
}
