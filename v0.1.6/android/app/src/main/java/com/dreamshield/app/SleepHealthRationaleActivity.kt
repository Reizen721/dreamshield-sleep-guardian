package com.dreamshield.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Required Health Connect permission rationale, also reachable from health-data settings. */
class SleepHealthRationaleActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.activityContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                        Text(text(R.string.copy_how_sleep_data_is_used), style = ComposeTheme.typography.headlineMedium)
                        Text(text(R.string.copy_with_your_permission_dreamshield_reads_recent_heal))
                        Text(text(R.string.copy_imports_stay_on_this_phone_clear_them_in_dreamshie))
                        Button(onClick = { finish() }) { Text(text(R.string.copy_back_789)) }
                    }
                }
            }
        }
    }
}
