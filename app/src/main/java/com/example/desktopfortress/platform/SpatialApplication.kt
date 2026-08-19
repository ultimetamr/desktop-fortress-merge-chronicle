package com.example.desktopfortress.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.desktopfortress.mainApp
import android.util.Log
import com.example.desktopfortress.audio.AudioManager
import com.example.desktopfortress.effect.EffectManager
import com.example.desktopfortress.manager.BoardManager
import com.example.desktopfortress.manager.SpatialManager
import com.example.desktopfortress.manager.MonsterManager
import com.example.desktopfortress.manager.TowerManager
import com.example.desktopfortress.manager.LevelManager
import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.manager.AchievementManager
import com.example.desktopfortress.manager.CodexManager
import com.example.desktopfortress.manager.DevelopManager
import com.example.desktopfortress.manager.GoldManager
import com.example.desktopfortress.manager.UIManager
import com.example.desktopfortress.manager.GameManager

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installGlobalExceptionHandler()
        PreferencesManager.initialize(this)
        DevelopManager.initialize()
        GoldManager.initialize()
        CodexManager.initialize()
        AchievementManager.initialize()
        UIManager.initialize()
        SpatialManager.initialize(this)
        BoardManager.initialize()
        MonsterManager.initialize()
        TowerManager.initialize()
        LevelManager.initialize()
        AudioManager.initialize()
        EffectManager.initialize()
        GameManager.initialize(this)
        launch(::mainApp)
    }

    private fun installGlobalExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("DesktopFortress", "Uncaught exception on ${thread.name}", throwable)
            GameManager.flushRecoveryCheckpoint()
            SpatialManager.stopSpatialPerception()
            previous?.uncaughtException(thread, throwable)
        }
    }
}
