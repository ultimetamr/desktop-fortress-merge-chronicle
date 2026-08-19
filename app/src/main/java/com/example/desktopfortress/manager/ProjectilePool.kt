package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.Projectile
import com.pico.spatial.core.math.Vector3
import java.util.ArrayDeque

class ProjectilePool(private val capacity: Int = 20) {
    private val inactive = ArrayDeque<Projectile>()
    private val active = ArrayDeque<Projectile>()
    private var nextId = 1
    private var sequence = 1L

    fun obtain(): Projectile {
        if (active.size >= capacity) recycle(active.removeFirst())
        val projectile = inactive.pollFirst() ?: Projectile(nextId++)
        projectile.active = true
        projectile.sequence = sequence++
        active.addLast(projectile)
        return projectile
    }

    fun recycle(projectile: Projectile) {
        if (!projectile.active && projectile in inactive) return
        active.remove(projectile)
        projectile.deactivate()
        inactive.addLast(projectile)
    }

    fun activeSnapshot(): List<Projectile> = active.toList()
    fun activeCount(): Int = active.size

    fun translateWorld(delta: Vector3) {
        active.forEach { it.position += delta }
    }

    fun clear() {
        active.forEach(Projectile::deactivate)
        inactive.clear()
        active.clear()
    }
}
