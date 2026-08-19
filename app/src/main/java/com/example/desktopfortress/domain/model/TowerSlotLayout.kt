package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3

/**
 * One meter-space contract shared by inventory logic and its Stage presentation.
 * +Z is the near edge of the horizontal board, toward the player.
 */
object TowerSlotLayout {
    const val SLOT_COUNT = 6
    const val SLOT_DEPTH_METERS = .13f
    const val SLOT_FRONT_GAP_METERS = .022f
    const val SLOT_SIDE_GAP_METERS = .012f
    const val PREVIEW_SCALE = .78f

    const val SELL_ZONE_SIZE_METERS = .14f
    const val SELL_ZONE_SIDE_GAP_METERS = .028f

    fun slotCenter(board: Board, slotIndex: Int): Vector3 {
        require(slotIndex in 0 until SLOT_COUNT) { "slotIndex must be in 0 until $SLOT_COUNT" }
        val slotWidth = board.widthMeters / SLOT_COUNT
        return Vector3(
            -board.widthMeters / 2f + slotWidth * (slotIndex + .5f),
            .030f,
            board.depthMeters / 2f + SLOT_FRONT_GAP_METERS + SLOT_DEPTH_METERS / 2f,
        )
    }

    fun slotTrayWidth(board: Board): Float =
        (board.widthMeters / SLOT_COUNT - SLOT_SIDE_GAP_METERS).coerceAtLeast(.04f)

    fun sellZoneCenter(board: Board): Vector3 = Vector3(
        board.widthMeters / 2f + SELL_ZONE_SIDE_GAP_METERS + SELL_ZONE_SIZE_METERS / 2f,
        .022f,
        board.depthMeters / 2f + SLOT_FRONT_GAP_METERS + SLOT_DEPTH_METERS / 2f,
    )

    fun isInSellZone(board: Board, boardLocalPosition: Vector3): Boolean {
        val center = sellZoneCenter(board)
        val half = SELL_ZONE_SIZE_METERS / 2f
        return boardLocalPosition.x in (center.x - half)..(center.x + half) &&
            boardLocalPosition.z in (center.z - half)..(center.z + half)
    }
}
