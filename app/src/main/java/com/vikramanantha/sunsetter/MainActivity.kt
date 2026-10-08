package com.vikramanantha.sunsetter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vikramanantha.sunsetter.ui.App
import com.vikramanantha.sunsetter.ui.LampViewModel
import com.vikramanantha.sunsetter.ui.SunsetterTheme

class MainActivity : ComponentActivity() {
    private val vm: LampViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SunsetterTheme { App(vm) } }
    }

    // The lamp takes one connection at a time, so only hold it while the app is on screen.
    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        super.onStop()
        vm.stop()
    }
}
