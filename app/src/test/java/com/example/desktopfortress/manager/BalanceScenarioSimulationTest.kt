package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.MemoryPreferenceStore
import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.CellType
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.TowerOperationResult
import com.example.desktopfortress.domain.model.TowerPurchaseResult
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.domain.model.WaveRuntimeState
import com.example.desktopfortress.effect.EffectManager
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
class BalanceScenarioSimulationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PreferencesManager.initializeForTesting(MemoryPreferenceStore())
        DevelopManager.refresh()
        CodexManager.refresh()
        AchievementManager.refresh()
        BoardManager.initialize()
        TowerManager.initialize()
        MonsterManager.initialize()
        EffectManager.destroy()
        EffectManager.initialize()
        LevelManager.initialize()
        LevelManager.setFlowListener(null)
        LevelManager.selectLevel(1)
        LevelManager.prepareSelectedLevel()
        InMemoryGameRepository.updateGameState(GameState.PREPARE)
    }

    @After
    fun tearDown() {
        TowerManager.resetSession()
        MonsterManager.clear()
        EffectManager.destroy()
        InMemoryGameRepository.updateGameState(GameState.MAIN_MENU)
        Dispatchers.resetMain()
    }

    @Test
    fun levelOneWithoutTowersFailsDuringTheFirstWave() {
        InMemoryGameRepository.updateGameState(GameState.FIGHTING)
        assertTrue(LevelManager.startOrResumeWave())

        repeat(10_000) {
            LevelManager.update(.02f)
            if (LevelManager.runtimeState.value.waveState == WaveRuntimeState.FAILED) return@repeat
        }

        val result = LevelManager.runtimeState.value
        assertEquals(WaveRuntimeState.FAILED, result.waveState)
        assertEquals(0, result.endpointHealth)
        assertEquals(1, result.currentWave)
    }

    @Test
    fun levelOneSuggestedTwoTowerEconomyClearsWithoutEasyFullStars() {
        val rankedCells = BoardManager.board.value.cells
            .filter { it.type == CellType.PLACEABLE }
            .sortedByDescending { cell ->
                val cellPosition = BoardManager.board.value.cellLocalCenter(cell.coordinate)
                BoardManager.board.value.pathPoints.count { path ->
                    val dx = path.localPosition.x - cellPosition.x
                    val dz = path.localPosition.z - cellPosition.z
                    dx * dx + dz * dz <= .45f * .45f
                }
            }
            .map { it.coordinate }

        // With only two leaks available, use the strongest visible path-coverage cells.
        val ordinaryCells = rankedCells
        placePurchasedArcher(ordinaryCells[0])
        InMemoryGameRepository.updateGameState(GameState.FIGHTING)
        assertTrue(LevelManager.startOrResumeWave())
        var secondTowerPlaced = false
        repeat(20_000) {
            LevelManager.update(.02f)
            TowerManager.update(.02f)
            when (LevelManager.runtimeState.value.waveState) {
                WaveRuntimeState.BETWEEN_WAVES -> {
                    InMemoryGameRepository.updateGameState(GameState.WAVE_PAUSE)
                    if (!secondTowerPlaced && GoldManager.getCurrentGold() >= 100) {
                        placePurchasedArcher(ordinaryCells.first { BoardManager.cellAt(it)?.tower == null })
                        secondTowerPlaced = true
                    }
                    assertTrue(LevelManager.startOrResumeWave())
                    InMemoryGameRepository.updateGameState(GameState.FIGHTING)
                }
                WaveRuntimeState.COMPLETED, WaveRuntimeState.FAILED -> return@repeat
                else -> Unit
            }
        }

        val result = LevelManager.runtimeState.value
        assertEquals(WaveRuntimeState.COMPLETED, result.waveState)
        assertTrue(
            "A deliberate level-one defence should clear while preserving the ten-health pressure: $result",
            result.endpointHealth in 1..10,
        )
    }

    private fun placePurchasedArcher(coordinate: CellCoordinate) {
        val purchase = TowerManager.purchaseToSlot(TowerType.ARCHER)
        assertTrue(purchase is TowerPurchaseResult.Stored)
        val slot = (purchase as TowerPurchaseResult.Stored).slotIndex
        assertTrue(TowerManager.beginSlotDrag(slot))
        assertTrue(TowerManager.confirmSelectionAtCell(coordinate) is TowerOperationResult.Placed)
    }
}
