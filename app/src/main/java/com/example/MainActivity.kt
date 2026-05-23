package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.NoteRepository
import com.example.ui.NoteAppScreen
import com.example.ui.NoteViewModel
import com.example.ui.NoteViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = NoteRepository(database.noteDao())
        
        setContent {
            MyApplicationTheme {
                val viewModel: NoteViewModel = viewModel(
                    factory = NoteViewModelFactory(repository)
                )
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    NoteAppScreen(viewModel = viewModel)
                }
            }
        }
    }
}
