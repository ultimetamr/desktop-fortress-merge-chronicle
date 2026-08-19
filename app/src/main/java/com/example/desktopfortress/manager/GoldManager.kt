package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.domain.model.LevelCatalog
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.GoldChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GoldManager : BaseManager() {
    private const val MAX_GOLD = 999_999_999
    private val mutableGold = MutableStateFlow(0)
    val gold: StateFlow<Int> = mutableGold.asStateFlow()
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        mutableGold.value = mutableGold.value.coerceIn(0, MAX_GOLD)
        initialized = true
    }

    fun initLevel(levelId: Int) {
        val base = LevelCatalog.get(levelId).initialGold
        setGold(DevelopManager.applyBonus(base, DevelopType.STARTING_GOLD), countLifetime = false)
    }

    fun addGold(amount: Int) {
        if (amount <= 0) {
            repairIfNeeded()
            return
        }
        val before = getCurrentGold()
        val next = (before.toLong() + amount.toLong()).coerceIn(0L, MAX_GOLD.toLong()).toInt()
        mutableGold.value = next
        PreferencesManager.addLifetimeGold(next - before)
        EventBus.tryEmit(GoldChanged(before, next))
    }

    fun costGold(amount: Int): Boolean {
        if (amount < 0) {
            repairIfNeeded()
            return false
        }
        if (amount == 0) return true
        val before = getCurrentGold()
        if (before < amount) return false
        mutableGold.value = before - amount
        EventBus.tryEmit(GoldChanged(before, mutableGold.value))
        return true
    }

    /** Reverses a failed transaction without counting the rollback as earned lifetime gold. */
    fun refundGold(amount: Int) {
        if (amount <= 0) return
        val restored = (getCurrentGold().toLong() + amount.toLong()).coerceAtMost(MAX_GOLD.toLong()).toInt()
        setGold(restored, countLifetime = false)
    }

    fun getCurrentGold(): Int {
        repairIfNeeded()
        return mutableGold.value
    }

    fun fillForDebug() {
        setGold(MAX_GOLD, countLifetime = false)
    }

    fun restoreCheckpoint(value: Int) {
        setGold(value, countLifetime = false)
    }

    internal fun resetForTesting(value: Int = 0) {
        mutableGold.value = value.coerceIn(0, MAX_GOLD)
    }

    override fun destroy() {
        mutableGold.value = 0
        initialized = false
        cancelScope()
    }

    private fun setGold(value: Int, countLifetime: Boolean) {
        val before = getCurrentGold()
        val next = value.coerceIn(0, MAX_GOLD)
        mutableGold.value = next
        if (countLifetime && next > before) PreferencesManager.addLifetimeGold(next - before)
        EventBus.tryEmit(GoldChanged(before, next))
    }

    private fun repairIfNeeded() {
        if (mutableGold.value < 0) mutableGold.value = 0
        if (mutableGold.value > MAX_GOLD) mutableGold.value = MAX_GOLD
    }
}
