package com.s4nj1th.slauncher.helper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.s4nj1th.slauncher.R

class FakeHomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_home)
    }
}