package com.example.desktopfortress.effect

import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.manager.BaseManager
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TowerEffect {
    val worldPosition: Vector3

    data class Placement(override val worldPosition: Vector3, val type: TowerType) : TowerEffect
    data class Merge(override val worldPosition: Vector3, val type: TowerType, val level: Int) : TowerEffect
    data class Hit(override val worldPosition: Vector3, val type: TowerType) : TowerEffect
    data class Sell(override val worldPosition: Vector3) : TowerEffect
}

data class TowerEffectSnapshot(
    val id: Long,
    val effect: TowerEffect,
    /** 0 at spawn, 1 when the visual should disappear. */
    val progress: Float,
)

object EffectManager : BaseManager() {
    private val mutableEffects = MutableSharedFlow<TowerEffect>(extraBufferCapacity = 32)
    val effects: SharedFlow<TowerEffect> = mutableEffects.asSharedFlow()
    private data class ActiveEffect(val id: Long, val effect: TowerEffect, var age: Float, val duration: Float)
    private val activeEffects = mutableListOf<ActiveEffect>()
    private val mutableEffectSnapshots = MutableStateFlow<List<TowerEffectSnapshot>>(emptyList())
    val effectSnapshots: StateFlow<List<TowerEffectSnapshot>> = mutableEffectSnapshots.asStateFlow()
    private var nextId = 1L
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        initialized = true
    }

    fun showPlacementPulse(position: Vector3, type: TowerType) {
        emit(TowerEffect.Placement(position, type), .35f)
    }

    fun showMergeBurst(position: Vector3, type: TowerType, level: Int) {
        emit(TowerEffect.Merge(position, type, level), .65f)
    }

    fun showHit(position: Vector3, type: TowerType) {
        emit(TowerEffect.Hit(position, type), .25f)
    }

    fun showSell(position: Vector3) {
        emit(TowerEffect.Sell(position), .40f)
    }

    /** Removes stale ground pulses when a tower at this position is picked up again. */
    fun clearAt(position: Vector3, radiusMeters: Float = .10f) {
        val radiusSquared = radiusMeters * radiusMeters
        val changed = activeEffects.removeAll { active ->
            val dx = active.effect.worldPosition.x - position.x
            val dz = active.effect.worldPosition.z - position.z
            dx * dx + dz * dz <= radiusSquared
        }
        if (changed) publish()
    }

    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f || !deltaSeconds.isFinite()) return
        activeEffects.forEach { it.age += deltaSeconds }
        activeEffects.removeAll { it.age >= it.duration }
        publish()
    }

    override fun destroy() {
        activeEffects.clear()
        publish()
        initialized = false
        cancelScope()
    }

    private fun emit(effect: TowerEffect, duration: Float) {
        mutableEffects.tryEmit(effect)
        activeEffects += ActiveEffect(nextId++, effect, 0f, duration)
        if (activeEffects.size > 16) activeEffects.removeAt(0)
        publish()
    }

    private fun publish() {
        mutableEffectSnapshots.value = activeEffects.map {
            TowerEffectSnapshot(it.id, it.effect, (it.age / it.duration).coerceIn(0f, 1f))
        }
    }
}
