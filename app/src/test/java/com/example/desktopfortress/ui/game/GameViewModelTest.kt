package com.example.desktopfortress.ui.game

import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        InMemoryGameRepository.updateGameState(GameState.MAIN_MENU)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateIsMainMenu() = runTest(dispatcher) {
        val viewModel = activeViewModel()
        advanceUntilIdle()
        assertEquals(GameState.MAIN_MENU, viewModel.uiState.value.gameState)
    }

    @Test
    fun grantedPermissionIsReflectedInState() = runTest(dispatcher) {
        val viewModel = activeViewModel()
        viewModel.onEvent(GameUiEvent.PermissionsResult(true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.permissionsGranted)
    }

    @Test
    fun pauseEventTransitionsToPaused() = runTest(dispatcher) {
        val viewModel = activeViewModel()
        viewModel.pauseGame()
        advanceUntilIdle()
        assertEquals(GameState.PAUSED, viewModel.uiState.value.gameState)
    }

    @Test
    fun dismissMessageClearsEventBusMessage() = runTest(dispatcher) {
        val viewModel = activeViewModel()
        advanceUntilIdle()
        EventBus.emit(UserMessage("test message"))
        advanceUntilIdle()
        assertEquals("test message", viewModel.uiState.value.message)
        viewModel.onEvent(GameUiEvent.DismissMessage)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.message)
    }

    private fun kotlinx.coroutines.test.TestScope.activeViewModel(): GameViewModel {
        val viewModel = GameViewModel(InMemoryGameRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        return viewModel
    }
}
