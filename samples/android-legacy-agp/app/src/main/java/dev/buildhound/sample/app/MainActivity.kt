package dev.buildhound.sample.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.buildhound.sample.core.common.Greeter

/** Minimal launcher activity — exists so the sample is a real, buildable Android app. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = Greeter.greeting("BuildHound") })
    }
}
