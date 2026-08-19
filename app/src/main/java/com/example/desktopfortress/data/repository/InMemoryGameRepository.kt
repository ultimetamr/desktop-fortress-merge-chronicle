package com.example.desktopfortress.data.repository

import com.example.desktopfortress.domain.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object InMemoryGameRepository : GameRepository {
    private val mutableGameState = MutableStateFlow(GameState.MAIN_MENU)
    override val gameState = mutableGameState.asStateFlow()

    override fun updateGameState(state: GameState) {
        mutableGameState.value = state
    }
}
