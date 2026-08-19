package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.MemoryPreferenceStore
import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.StateTransitionResult
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.effect.EffectManager
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameManagerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PreferencesManager.initializeForTesting(MemoryPreferenceStore())
        DevelopManager.refresh()
        InMemoryGameRepository.updateGameState(GameState.MAIN_MENU)
        GameManager.destroy()
        GameManager.initialize()
    }

    @After
    fun tearDown() {
        GameManager.destroy()
        InMemoryGameRepository.updateGameState(GameState.MAIN_MENU)
        Dispatchers.resetMain()
    }

    @Test
    fun rejectsIllegalMenuToFightingJump() {
        val result = GameManager.requestTransition(GameState.FIGHTING)
        assertTrue(result is StateTransitionResult.Rejected)
        assertEquals(GameState.MAIN_MENU, InMemoryGameRepository.gameState.value)
    }

    @Test
    fun menuLevelSelectPauseResumeUsesRememberedState() {
        assertTrue(GameManager.showLevelSelect() is StateTransitionResult.Changed)
        assertEquals(GameState.LEVEL_SELECT, InMemoryGameRepository.gameState.value)
        assertTrue(GameManager.pause())
        assertEquals(GameState.PAUSED, InMemoryGameRepository.gameState.value)
        assertTrue(GameManager.resume())
        assertEquals(GameState.LEVEL_SELECT, InMemoryGameRepository.gameState.value)
    }

    @Test
    fun startGameActionTransitionsPreparedLevelToFighting() {
        LevelManager.selectLevel(1)
        LevelManager.prepareSelectedLevel()
        InMemoryGameRepository.updateGameState(GameState.PREPARE)

        assertTrue(GameManager.startOrResumeFight())
        assertEquals(GameState.FIGHTING, InMemoryGameRepository.gameState.value)
    }

    @Test
    fun prepareStateAdvancesAndClearsTransientPlacementRings() {
        EffectManager.destroy()
        EffectManager.initialize()
        InMemoryGameRepository.updateGameState(GameState.PREPARE)
        EffectManager.showPlacementPulse(Vector3.ZERO, TowerType.ARCHER)
        assertTrue(EffectManager.effectSnapshots.value.isNotEmpty())

        repeat(5) { GameManager.update(.10f) }

        assertTrue(EffectManager.effectSnapshots.value.isEmpty())
        EffectManager.destroy()
    }

    @Test
    fun planarHostPauseDoesNotPauseAnOpenGameplayStage() {
        InMemoryGameRepository.updateGameState(GameState.PREPARE)
        GameManager.onGameplayStageOpened()

        GameManager.onAppBackgrounded()

        assertEquals(GameState.PREPARE, InMemoryGameRepository.gameState.value)
    }

    @Test
    fun gameplayStageVisibilityLossPausesAfterItWasOnstage() {
        InMemoryGameRepository.updateGameState(GameState.PREPARE)
        GameManager.onGameplayStageOpened()
        GameManager.onGameplayStageOnstageChanged(true)

        GameManager.onGameplayStageOnstageChanged(false)

        assertEquals(GameState.PAUSED, InMemoryGameRepository.gameState.value)
    }

    @Test
    fun returnToMenuClearsStagePauseLatch() {
        InMemoryGameRepository.updateGameState(GameState.WAVE_PAUSE)
        GameManager.onGameplayStageOpened()
        GameManager.onGameplayStageOnstageChanged(true)

        GameManager.returnToMainMenu()
        GameManager.onAppBackgrounded()

        assertEquals(GameState.MAIN_MENU, InMemoryGameRepository.gameState.value)
    }
}
