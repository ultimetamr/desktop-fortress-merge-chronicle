package com.example.desktopfortress.data.repository

import com.example.desktopfortress.domain.model.GameState
import kotlinx.coroutines.flow.StateFlow

interface GameRepository {
    val gameState: StateFlow<GameState>
    fun updateGameState(state: GameState)
}
