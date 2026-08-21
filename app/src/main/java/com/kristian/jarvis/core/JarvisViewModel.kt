package com.kristian.jarvis.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/**
 * Fallback owner of the engine for the one case the service can't cover:
 * microphone permission denied, where an always-listening service would be
 * both rejected by Android 14+ and pointless. Typing still works.
 *
 * With the microphone granted, the service owns the engine and this is never
 * touched - hence the lazy construction.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val lazyEngine = lazy { JarvisEngine(getApplication(), viewModelScope) }

    val localEngine: JarvisEngine by lazyEngine

    override fun onCleared() {
        if (lazyEngine.isInitialized()) localEngine.release()
        super.onCleared()
    }
}
