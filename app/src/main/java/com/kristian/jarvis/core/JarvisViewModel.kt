package com.kristian.jarvis.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/**
 * Holds the engine across configuration changes so a rotation doesn't drop the
 * conversation or restart the microphone.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    val engine = JarvisEngine(application, viewModelScope)

    val uiState = engine.uiState

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
