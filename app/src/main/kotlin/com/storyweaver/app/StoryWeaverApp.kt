package com.storyweaver.app

import android.app.Application
import com.storyweaver.app.di.AppContainer

class StoryWeaverApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
