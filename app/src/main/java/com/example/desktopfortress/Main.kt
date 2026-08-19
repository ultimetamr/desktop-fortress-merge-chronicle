package com.example.desktopfortress

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.example.desktopfortress.content.HomePage
import com.example.desktopfortress.ui.game.GameStageScreen
import com.pico.spatial.ui.foundation.dsl.Stage

const val GAME_STAGE_ID = "GameStage"
const val MAIN_WINDOW_ID = "MainWindow"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme {
                HomePage()
            }
        }
        Stage(id = GAME_STAGE_ID) {
            PicoTheme {
                GameStageScreen()
            }
        }
    }
