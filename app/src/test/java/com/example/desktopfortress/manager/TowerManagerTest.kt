package com.example.desktopfortress.manager

import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.data.local.MemoryPreferenceStore
import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.CellType
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.domain.model.TowerOperationResult
import com.example.desktopfortress.domain.model.TowerPurchaseResult
import com.example.desktopfortress.domain.model.TowerDragSource
import com.example.desktopfortress.domain.model.TowerSlotLayout
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TowerManagerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PreferencesManager.initializeForTesting(MemoryPreferenceStore())
        DevelopManager.refresh()
        CodexManager.refresh()
        AchievementManager.refresh()
        BoardManager.resetForCalibration()
        TowerManager.initialize()
        EffectManager.destroy()
        EffectManager.initialize()
        MonsterManager.initialize()
        TowerManager.resetSession(5_000)
        InMemoryGameRepository.updateGameState(GameState.PREPARE)
    }

    @After
    fun tearDown() {
        TowerManager.resetSession()
        EffectManager.destroy()
        InMemoryGameRepository.updateGameState(GameState.MAIN_MENU)
        Dispatchers.resetMain()
    }

    @Test
    fun emptyPlaceableCellAcceptsTower() {
        val cell = placeableCoordinates().first()
        val slot = purchaseSlot(TowerType.ARCHER)
        assertTrue(TowerManager.beginSlotDrag(slot))
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(cell))
        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Placed)
        assertEquals(TowerType.ARCHER.name, BoardManager.cellAt(cell)?.tower?.kind)
        assertEquals(null, TowerManager.inventory.value.slots[slot].item)
    }

    @Test
    fun sdkResolvedCellTargetHighlightsAndPlacesOnRelease() {
        val cell = placeableCoordinates().first()
        val slot = purchaseSlot(TowerType.ARCHER)
        assertTrue(TowerManager.beginSlotDrag(slot))

        val preview = TowerManager.updateDragToCell(cell)

        assertEquals(cell, preview?.snappedCell)
        assertEquals(cell, BoardManager.board.value.highlightedCell)
        assertTrue(TowerManager.releaseDrag() is TowerOperationResult.Placed)
        assertEquals(TowerType.ARCHER.name, BoardManager.cellAt(cell)?.tower?.kind)
    }

    @Test
    fun rayHoverMovesPreviewAndRecomputesCellValidity() {
        val placeable = placeableCoordinates().first()
        val path = BoardManager.board.value.cells.first { it.type == CellType.PATH }.coordinate
        val slot = purchaseSlot(TowerType.ARCHER)
        assertTrue(TowerManager.selectInventorySlot(slot))

        val valid = requireNotNull(TowerManager.updateDragToCell(placeable))
        assertEquals(placeable, BoardManager.board.value.highlightedCell)
        assertEquals(com.example.desktopfortress.domain.model.DragValidity.VALID_PLACE, valid.validity)

        val invalid = requireNotNull(TowerManager.updateDragToCell(path))
        assertEquals(path, BoardManager.board.value.highlightedCell)
        assertEquals(com.example.desktopfortress.domain.model.DragValidity.INVALID, invalid.validity)
    }

    @Test
    fun twoTapSelectionPlacesOnExactSdkResolvedCell() {
        val target = placeableCoordinates().last()
        val slot = purchaseSlot(TowerType.BALLISTA)

        assertTrue(TowerManager.selectInventorySlot(slot))
        val result = TowerManager.confirmSelectionAtCell(target)

        assertTrue(result is TowerOperationResult.Placed)
        assertEquals(target, (result as TowerOperationResult.Placed).coordinate)
        assertEquals(TowerType.BALLISTA.name, BoardManager.cellAt(target)?.tower?.kind)
        assertEquals(null, TowerManager.inventory.value.slots[slot].item)
    }

    @Test
    fun selectingSameSlotAgainKeepsSelectionWithoutConsumingIt() {
        val slot = purchaseSlot(TowerType.FROST)

        assertTrue(TowerManager.selectInventorySlot(slot))
        assertTrue(TowerManager.selectInventorySlot(slot))

        assertEquals(slot, (TowerManager.dragPreview.value?.source as? TowerDragSource.InventorySlot)?.slotIndex)
        assertEquals(TowerType.FROST, TowerManager.inventory.value.slots[slot].item?.type)
    }

    @Test
    fun purchaseStoresConfigurationInFirstEmptySlotAndChargesImmediately() {
        val before = TowerManager.gold.value
        val cost = com.example.desktopfortress.domain.model.TowerBalanceTable.get(TowerType.ARCHER, 1).cost
        val result = TowerManager.purchaseToSlot(TowerType.ARCHER)

        assertTrue(result is TowerPurchaseResult.Stored)
        assertEquals(0, (result as TowerPurchaseResult.Stored).slotIndex)
        assertEquals(TowerType.ARCHER, TowerManager.inventory.value.slots[0].item?.type)
        assertEquals(before - cost, TowerManager.gold.value)
        assertTrue(TowerManager.towers.value.isEmpty())
        assertNull(TowerManager.dragPreview.value)
    }

    @Test
    fun purchasedTowerDragStartsAtItsWorldSlotInFrontOfBoard() {
        val slot = purchaseSlot(TowerType.ARCHER)
        assertTrue(TowerManager.beginSlotDrag(slot))
        val board = BoardManager.board.value
        val local = board.worldToLocal(requireNotNull(TowerManager.dragPreview.value).worldPosition)
        val expected = TowerSlotLayout.slotCenter(board, slot)

        assertEquals(expected.x, local.x, .0001f)
        assertEquals(expected.z, local.z, .0001f)
        assertTrue(local.z > board.depthMeters / 2f)
        assertFalse(TowerSlotLayout.isInSellZone(board, local))
    }

    @Test
    fun purchasedTowerRemainsStoredUntilPlayerExplicitlySelectsItsSlot() {
        val result = TowerManager.purchaseToSlot(TowerType.ARCHER)

        assertTrue(result is TowerPurchaseResult.Stored)
        val slot = (result as TowerPurchaseResult.Stored).slotIndex
        assertNull(TowerManager.dragPreview.value)
        assertEquals(TowerType.ARCHER, TowerManager.inventory.value.slots[slot].item?.type)
        assertTrue(TowerManager.towers.value.isEmpty())

        assertTrue(TowerManager.selectInventorySlot(slot))
        assertEquals(slot, (TowerManager.dragPreview.value?.source as? TowerDragSource.InventorySlot)?.slotIndex)
    }

    @Test
    fun sellZoneIsRightOfInventoryRackWithoutOverlap() {
        val board = BoardManager.board.value
        val sell = TowerSlotLayout.sellZoneCenter(board)

        assertTrue(sell.x > board.widthMeters / 2f)
        repeat(TowerSlotLayout.SLOT_COUNT) { slot ->
            assertFalse(TowerSlotLayout.isInSellZone(board, TowerSlotLayout.slotCenter(board, slot)))
        }
    }

    @Test
    fun fullInventoryRejectsPurchaseWithoutLosingGold() {
        repeat(TowerManager.INVENTORY_CAPACITY) { purchaseSlot(TowerType.ARCHER) }
        val before = TowerManager.gold.value

        val result = TowerManager.purchaseToSlot(TowerType.FROST)

        assertTrue(result is TowerPurchaseResult.Rejected)
        assertEquals(before, TowerManager.gold.value)
        assertTrue(TowerManager.inventory.value.isFull)
    }

    @Test
    fun equalTypeAndLevelMergeIntoNextTierAndRecycleInputs() {
        val cells = placeableCoordinates().take(2)
        val first = place(TowerType.FROST, cells[0]) as TowerOperationResult.Placed
        val second = place(TowerType.FROST, cells[1]) as TowerOperationResult.Placed

        assertTrue(TowerManager.beginExisting(first.towerId))
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(cells[1]))
        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Merged)
        assertEquals(2, (result as TowerOperationResult.Merged).newLevel)
        assertEquals(null, BoardManager.cellAt(cells[0])?.tower)
        assertEquals(2, BoardManager.cellAt(cells[1])?.tower?.level)
        assertFalse(TowerManager.towers.value.any { it.id == second.towerId })
    }

    @Test
    fun slotTowerUsesSharedMergeRuleAndConsumesSlot() {
        val cell = placeableCoordinates().first()
        place(TowerType.FROST, cell)
        val slot = purchaseSlot(TowerType.FROST)

        TowerManager.beginSlotDrag(slot)
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(cell))
        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Merged)
        assertEquals(2, (result as TowerOperationResult.Merged).newLevel)
        assertEquals(2, BoardManager.cellAt(cell)?.tower?.level)
        assertEquals(null, TowerManager.inventory.value.slots[slot].item)
    }

    @Test
    fun mismatchedTowerReturnsToOriginalCell() {
        val cells = placeableCoordinates().take(2)
        val archer = place(TowerType.ARCHER, cells[0]) as TowerOperationResult.Placed
        place(TowerType.EXPLOSIVE, cells[1])

        TowerManager.beginExisting(archer.towerId)
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(cells[1]))
        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Rejected)
        assertEquals(archer.towerId.toString(), BoardManager.cellAt(cells[0])?.tower?.id)
    }

    @Test
    fun invalidSlotDropKeepsItemInOriginalSlot() {
        val path = BoardManager.board.value.cells.first { it.type == CellType.PATH }.coordinate
        val slot = purchaseSlot(TowerType.BALLISTA)
        TowerManager.beginSlotDrag(slot)
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(path))

        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Rejected)
        assertEquals(TowerType.BALLISTA, TowerManager.inventory.value.slots[slot].item?.type)
        assertEquals(null, BoardManager.cellAt(path)?.tower)
    }

    @Test
    fun slotDropOnDifferentTowerFailsAndReturnsToSlot() {
        val cell = placeableCoordinates().first()
        place(TowerType.ARCHER, cell)
        val slot = purchaseSlot(TowerType.EXPLOSIVE)
        TowerManager.beginSlotDrag(slot)
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(cell))

        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Rejected)
        assertEquals(TowerType.EXPLOSIVE, TowerManager.inventory.value.slots[slot].item?.type)
        assertEquals(TowerType.ARCHER.name, BoardManager.cellAt(cell)?.tower?.kind)
    }

    @Test
    fun physicalViewDeltaMapsToBoardPlaneAndKeepsGroundHeight() {
        val slot = purchaseSlot(TowerType.ARCHER)
        TowerManager.beginSlotDrag(slot)
        val board = BoardManager.board.value
        val before = board.worldToLocal(requireNotNull(TowerManager.dragPreview.value).worldPosition)

        val updated = requireNotNull(TowerManager.updateDragByBoardViewMeters(.03f, -.04f))
        val after = board.worldToLocal(updated.worldPosition)

        assertEquals(before.x + .03f / board.transform.scale, after.x, .0001f)
        assertEquals(before.z - .04f / board.transform.scale, after.z, .0001f)
        assertEquals(BoardManager.board.value.transform.worldCenter.y, updated.worldPosition.y, .0001f)
    }

    @Test
    fun actualPointerCellRemainsAuthoritativeOverCapturedGestureDeltas() {
        val slot = purchaseSlot(TowerType.ARCHER)
        val cell = placeableCoordinates().first()
        TowerManager.beginSlotDrag(slot)
        val rayPreview = requireNotNull(TowerManager.updateDragToCell(cell))

        val afterCapturedDelta = requireNotNull(
            TowerManager.updateDragByBoardViewMeters(.4f, .4f, .4f),
        )

        assertEquals(cell, afterCapturedDelta.snappedCell)
        assertEquals(rayPreview.worldPosition, afterCapturedDelta.worldPosition)
        TowerManager.cancelDrag()
    }

    @Test
    fun fightingStateLocksAllEditOperations() {
        InMemoryGameRepository.updateGameState(GameState.FIGHTING)
        assertTrue(TowerManager.purchaseToSlot(TowerType.BALLISTA) is TowerPurchaseResult.Rejected)
        assertFalse(TowerManager.beginSlotDrag(0))
    }

    @Test
    fun wavePauseUnlocksExistingSlotAgain() {
        val slot = purchaseSlot(TowerType.BALLISTA)
        InMemoryGameRepository.updateGameState(GameState.FIGHTING)
        assertFalse(TowerManager.beginSlotDrag(slot))

        InMemoryGameRepository.updateGameState(GameState.WAVE_PAUSE)
        assertTrue(TowerManager.beginSlotDrag(slot))
    }

    @Test
    fun cancellingSlotDragReturnsConfigurationToItsSlot() {
        val slot = purchaseSlot(TowerType.EXPLOSIVE)
        TowerManager.beginSlotDrag(slot)

        TowerManager.cancelDrag()

        assertEquals(TowerType.EXPLOSIVE, TowerManager.inventory.value.slots[slot].item?.type)
        assertEquals(null, TowerManager.dragPreview.value)
    }

    @Test
    fun recoveryRoundTripPreservesLogicalSlotsWithoutCreatingEntities() {
        purchaseSlot(TowerType.ARCHER)
        purchaseSlot(TowerType.FROST)
        val recovery = TowerManager.recoveryInventorySlots()

        TowerManager.clearAll()
        TowerManager.restoreCheckpointInventory(recovery)

        assertEquals(TowerType.ARCHER, TowerManager.inventory.value.slots[0].item?.type)
        assertEquals(TowerType.FROST, TowerManager.inventory.value.slots[1].item?.type)
        assertTrue(TowerManager.towers.value.isEmpty())
    }

    @Test
    fun longPressSaleApiClearsSlotAndRefunds() {
        val slot = purchaseSlot(TowerType.ARCHER)
        val afterPurchase = TowerManager.gold.value

        val result = TowerManager.sellSlot(slot)

        assertTrue(result is TowerOperationResult.SlotSold)
        assertEquals(70, (result as TowerOperationResult.SlotSold).refund)
        assertEquals(afterPurchase + 70, TowerManager.gold.value)
        assertEquals(null, TowerManager.inventory.value.slots[slot].item)
    }

    @Test
    fun existingTowerSoldInSellZoneRefundsSixtyPercent() {
        val cell = placeableCoordinates().first()
        val placed = place(TowerType.ARCHER, cell) as TowerOperationResult.Placed
        assertEquals(4_900, TowerManager.gold.value)

        TowerManager.beginExisting(placed.towerId)
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(cell), overSellZone = true)
        val result = TowerManager.releaseDrag()

        assertTrue(result is TowerOperationResult.Sold)
        assertEquals(70, (result as TowerOperationResult.Sold).refund)
        assertEquals(4_970, TowerManager.gold.value)
        assertEquals(null, BoardManager.cellAt(cell)?.tower)
    }

    @Test
    fun saleRefundDropsAfterLevelThreeAndDevelopmentCapsAtNinetyPercent() {
        assertEquals(.70f, TowerManager.sellRefundRatioForLevel(3), .0001f)
        assertEquals(.40f, TowerManager.sellRefundRatioForLevel(4), .0001f)

        PreferencesManager.setDevelopLevel(DevelopType.CRYSTAL_REWARD, 10)
        DevelopManager.refresh()

        assertEquals(.90f, TowerManager.sellRefundRatioForLevel(1), .0001f)
        assertEquals(.90f, TowerManager.sellRefundRatioForLevel(20), .0001f)
    }

    @Test
    fun selectedBoardTowerCanBeSoldFromHudAction() {
        val cell = placeableCoordinates().first()
        val placed = place(TowerType.ARCHER, cell) as TowerOperationResult.Placed
        val goldBeforeSale = TowerManager.gold.value

        assertTrue(TowerManager.beginExisting(placed.towerId))
        val result = TowerManager.sellSelectedWeapon()

        assertTrue(result is TowerOperationResult.Sold)
        assertEquals(null, BoardManager.cellAt(cell)?.tower)
        assertTrue(TowerManager.gold.value > goldBeforeSale)
        assertEquals(null, TowerManager.dragPreview.value)
    }

    @Test
    fun pickingBoardTowerClearsOldCellHighlightAndPlacementRing() {
        val cell = placeableCoordinates().first()
        val placed = place(TowerType.FROST, cell) as TowerOperationResult.Placed
        assertTrue(EffectManager.effectSnapshots.value.isNotEmpty())
        BoardManager.previewCell(cell)

        assertTrue(TowerManager.beginExisting(placed.towerId))

        assertEquals(null, BoardManager.board.value.highlightedCell)
        assertTrue(EffectManager.effectSnapshots.value.isEmpty())
    }

    @Test
    fun movingBoardTowerClearsOriginalCellAndDragState() {
        val cells = placeableCoordinates().take(2)
        val placed = place(TowerType.BALLISTA, cells[0]) as TowerOperationResult.Placed

        assertTrue(TowerManager.beginExisting(placed.towerId))
        val result = TowerManager.confirmSelectionAtCell(cells[1])

        assertTrue(result is TowerOperationResult.Placed)
        assertEquals(null, BoardManager.cellAt(cells[0])?.tower)
        assertEquals(placed.towerId.toString(), BoardManager.cellAt(cells[1])?.tower?.id)
        assertEquals(null, BoardManager.board.value.highlightedCell)
        assertEquals(null, TowerManager.dragPreview.value)
    }

    @Test
    fun autoAttackPrioritizesMonsterFurthestAlongPath() {
        val cell = placeableCoordinates().first()
        place(TowerType.ARCHER, cell)
        val towerPosition = BoardManager.cellWorldCenter(cell)
        val near = MonsterManager.spawn(towerPosition + Vector3(.12f, 0f, 0f), 100f, .2f)
        val far = MonsterManager.spawn(towerPosition + Vector3(.12f, 0f, 0f), 100f, .8f)
        InMemoryGameRepository.updateGameState(GameState.FIGHTING)

        repeat(20) { TowerManager.update(.05f) }

        assertTrue(far.health < near.health)
    }

    private fun place(type: TowerType, coordinate: com.example.desktopfortress.domain.model.CellCoordinate): TowerOperationResult {
        val slot = purchaseSlot(type)
        TowerManager.beginSlotDrag(slot)
        TowerManager.updateDragWorld(BoardManager.cellWorldCenter(coordinate))
        return TowerManager.releaseDrag()
    }

    private fun purchaseSlot(type: TowerType): Int =
        (TowerManager.purchaseToSlot(type) as TowerPurchaseResult.Stored).slotIndex

    private fun placeableCoordinates() = BoardManager.board.value.cells
        .filter { it.type == CellType.PLACEABLE }
        .map { it.coordinate }
}
