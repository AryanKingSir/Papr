package com.papr.scanner

import android.app.Application
import com.papr.scanner.di.AppContainer

class PaprApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.pageTextExtractor.extractMissingInBackground()
    }
}
