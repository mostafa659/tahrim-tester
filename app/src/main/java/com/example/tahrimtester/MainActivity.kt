package com.example.tahrimtester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = (application as TahrimApp).reciter
        r.load()
        setContent { TahrimRoot(r) }
    }

    override fun onStop() {
        super.onStop()
        (application as TahrimApp).reciter.onBackground()
    }
}
