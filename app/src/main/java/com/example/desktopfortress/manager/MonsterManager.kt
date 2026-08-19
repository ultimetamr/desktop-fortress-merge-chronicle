package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.BaseMonster
import com.example.desktopfortress.domain.model.MonsterAction
import com.example.desktopfortress.domain.model.MonsterConfig
import com.example.desktopfortress.domain.model.MonsterConfigTable
import com.example.desktopfortress.domain.model.MonsterSnapshot
import com.example.desktopfortress.domain.model.MonsterType
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns live monsters and enforces the level-configured 20-entity concurrency budget. */
object MonsterManager : BaseManager() {
    const val MAX_ACTIVE_MONSTERS = 20

    interface Listener {
        fun onMonsterKilled(monster: BaseMonster, goldReward: Int)
        fun onEndpointHit(monster: BaseMonster, damage: Int)
        fun onRemoteEndpointAttack(monster: BaseMonster, damage: Int)
    }

    private val pool = MonsterPool()
    private val monstersById = linkedMapOf<Long, BaseMonster>()
    private val mutableMonsters = MutableStateFlow<List<MonsterSnapshot>>(emptyList())
    val monsters: StateFlow<List<MonsterSnapshot>> = mutableMonsters.asStateFlow()
    private var listener: Listener? = null
    private var initialized = false
    private var nextInstanceId = 1L
    private var activeMonsterCap = MAX_ACTIVE_MONSTERS

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        initialized = true
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun configureActiveCap(cap: Int) {
        activeMonsterCap = cap.coerceIn(1, MAX_ACTIVE_MONSTERS)
    }

    /** Returns null at the concurrency cap; LevelManager keeps the spawn queued. */
    fun spawn(
        type: MonsterType,
        path: List<Vector3>,
        healthMultiplier: Float,
        speedMultiplier: Float,
        desktopHeight: Float,
        levelId: Int = 2,
    ): BaseMonster? {
        if (monstersById.size >= activeMonsterCap || path.isEmpty()) return null
        return activate(
            monster = pool.obtain(type),
            config = MonsterConfigTable.getForLevel(type, levelId),
            path = path,
            healthMultiplier = healthMultiplier,
            speedMultiplier = speedMultiplier,
            desktopHeight = desktopHeight,
        ).also { CodexManager.unlockMonster(type) }
    }

    /** Test/debug target retained for the tower integration harness. */
    fun spawn(worldPosition: Vector3, health: Float, pathProgress: Float = 0f): BaseMonster {
        require(monstersById.size < activeMonsterCap)
        val config = MonsterConfigTable.get(MonsterType.SMALL_BUG).copy(
            baseHealth = health,
            movementSpeedMetersPerSecond = 0f,
        )
        return activate(
            monster = pool.obtain(MonsterType.SMALL_BUG),
            config = config,
            path = listOf(worldPosition, worldPosition + Vector3(0f, 0f, 1f)),
            healthMultiplier = 1f,
            speedMultiplier = 1f,
            desktopHeight = worldPosition.y,
        ).also { it.pathProgress = pathProgress.coerceIn(0f, 1f) }
    }

    fun get(id: Long): BaseMonster? = monstersById[id]
    fun activeMonsters(): List<BaseMonster> = monstersById.values.filter(BaseMonster::isAlive)
    fun activeCount(): Int = monstersById.size

    fun translateWorld(delta: Vector3) {
        monstersById.values.forEach { it.translateWorld(delta) }
        publish()
    }
    internal fun cachedCount(type: MonsterType? = null): Int = pool.cachedCount(type)

    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f || !deltaSeconds.isFinite()) return
        val snapshot = monstersById.values.toList()
        snapshot.forEach { monster ->
            if (!monster.isAlive) {
                removeKilled(monster)
                return@forEach
            }
            when (val action = monster.updateMovement(deltaSeconds)) {
                is MonsterAction.ReachedEndpoint -> {
                    listener?.onEndpointHit(monster, action.damage)
                    removeAndRecycle(monster)
                }
                is MonsterAction.RemoteEndpointAttack -> {
                    listener?.onRemoteEndpointAttack(monster, action.damage)
                }
                null -> if (!monster.isAlive) removeKilled(monster)
            }
        }
        publish()
    }

    fun recycleAll() {
        monstersById.values.toList().forEach(::removeAndRecycle)
        publish()
    }

    fun clear() = recycleAll()

    override fun destroy() {
        recycleAll()
        pool.clear()
        listener = null
        activeMonsterCap = MAX_ACTIVE_MONSTERS
        initialized = false
        cancelScope()
    }

    private fun activate(
        monster: BaseMonster,
        config: MonsterConfig,
        path: List<Vector3>,
        healthMultiplier: Float,
        speedMultiplier: Float,
        desktopHeight: Float,
    ): BaseMonster {
        monster.activate(
            instanceId = nextInstanceId++,
            config = config,
            path = path,
            healthMultiplier = healthMultiplier,
            speedMultiplier = speedMultiplier,
            desktopHeight = desktopHeight,
        )
        monstersById[monster.id] = monster
        publish()
        return monster
    }

    private fun removeKilled(monster: BaseMonster) {
        if (monstersById.remove(monster.id) == null) return
        monster.die()
        listener?.onMonsterKilled(monster, monster.config.killGold)
        pool.recycle(monster)
    }

    private fun removeAndRecycle(monster: BaseMonster) {
        if (monstersById.remove(monster.id) != null) pool.recycle(monster)
    }

    private fun publish() {
        mutableMonsters.value = monstersById.values.map { monster ->
            MonsterSnapshot(
                poolObjectId = monster.poolObjectId,
                instanceId = monster.id,
                type = monster.type,
                worldPosition = monster.worldPosition,
                facingYawRadians = monster.facingYawRadians,
                healthRatio = if (monster.maxHealth <= 0f) 0f else monster.currentHealth / monster.maxHealth,
                visualScale = monster.visualScale,
            )
        }
    }
}
