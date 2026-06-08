package com.example.sdrvideoscanner

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.sdrvideoscanner.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val defaultIqPath = File(getExternalFilesDir(null), "input.cs16").absolutePath
        binding.sampleText.text = "Place input.cs16 at:\n$defaultIqPath"
        binding.diagnoseButton.setOnClickListener {
            binding.sampleText.text = diagnoseCs16File(defaultIqPath)
        }
    }

    /**
     * Opens a little-endian CS16 I/Q file and returns first-block diagnostics.
     */
    external fun diagnoseCs16File(path: String): String

    companion object {
        // Used to load the 'sdrvideoscanner' library on application startup.
        init {
            System.loadLibrary("sdrvideoscanner")
        }
    }
}
