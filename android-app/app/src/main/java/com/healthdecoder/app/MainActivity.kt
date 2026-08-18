package com.healthdecoder.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.healthdecoder.app.reminder.MedicineReminderManager
import com.healthdecoder.app.theme.MedicalScannerTheme

import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import android.net.Uri

class MainActivity : FragmentActivity() {
  companion object {
    val deepLinkUri = mutableStateOf<Uri?>(null)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MedicineReminderManager.createChannel(this)

    intent?.data?.let {
      deepLinkUri.value = it
    }

    enableEdgeToEdge()
    setContent {
      MedicalScannerTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let {
      deepLinkUri.value = it
    }
  }
}

