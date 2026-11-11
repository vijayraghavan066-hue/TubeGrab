package com.vj.spellbee

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.vj.spellbee.ui.BeeApp
import com.vj.spellbee.ui.BeeTheme

class MainActivity : ComponentActivity() {
    private val vm: BeeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BeeTheme { BeeApp(vm) } }
    }
}
