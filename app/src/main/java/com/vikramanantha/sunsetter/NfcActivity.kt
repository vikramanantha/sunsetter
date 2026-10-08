package com.vikramanantha.sunsetter

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vikramanantha.sunsetter.widget.setLampPower


class NfcActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.data?.path
        

        val target = when (path) {
            "/toggle" -> null
            "/off" -> false
            else -> { finish(); return }
        }
        lifecycleScope.launch {
            val result = setLampPower(this@NfcActivity, target)
            val message = when (result) {
                true -> "Lamp on"
                false -> "Lamp off"
                null -> "Something something something"
            }
            Toast.makeText(this@NfcActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

}
