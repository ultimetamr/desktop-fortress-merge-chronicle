package com.example.desktopfortress.data.local

import android.content.Context
import com.example.desktopfortress.domain.model.GameRecoveryCheckpoint
import com.example.desktopfortress.domain.model.TowerRecoveryItem
import com.example.desktopfortress.domain.model.TowerSlotRecoveryItem
import com.example.desktopfortress.domain.model.TowerType
import java.security.MessageDigest

/** Small checksummed crash-recovery record, deliberately separate from permanent progression. */
class GameRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): GameRecoveryCheckpoint? {
        val raw = preferences.getString(KEY_PAYLOAD, null) ?: return null
        if (preferences.getString(KEY_CHECKSUM, null) != checksum(raw)) {
            clear()
            return null
        }
        val parts = raw.split('|')
        if (parts.size !in 6..7) return null.also { clear() }
        val towers = parts[5].split(';').mapNotNull { token ->
            if (token.isBlank()) return@mapNotNull null
            val fields = token.split(':')
            val type = runCatching { TowerType.valueOf(fields.getOrNull(0).orEmpty()) }.getOrNull()
                ?: return@mapNotNull null
            TowerRecoveryItem(
                type = type,
                level = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                row = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
                column = fields.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null,
            )
        }
        val checkpoint = GameRecoveryCheckpoint(
            levelId = parts[0].toIntOrNull() ?: return null.also { clear() },
            waveNumber = parts[1].toIntOrNull() ?: return null.also { clear() },
            endpointHealth = parts[2].toIntOrNull() ?: return null.also { clear() },
            gold = parts[3].toIntOrNull() ?: return null.also { clear() },
            savedAtEpochMillis = parts[4].toLongOrNull() ?: return null.also { clear() },
            towers = towers,
            inventorySlots = parts.getOrNull(6).orEmpty().split(';').mapNotNull { token ->
                if (token.isBlank()) return@mapNotNull null
                val fields = token.split(':')
                val type = runCatching { TowerType.valueOf(fields.getOrNull(0).orEmpty()) }.getOrNull()
                    ?: return@mapNotNull null
                TowerSlotRecoveryItem(
                    type = type,
                    level = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                    slotIndex = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
                )
            },
        )
        return checkpoint.takeIf {
            it.levelId in 1..20 && it.waveNumber in 1..20 &&
                it.endpointHealth in 1..10_000 && it.gold in 0..999_999_999 &&
                it.inventorySlots.size <= 6 &&
                it.inventorySlots.map { slot -> slot.slotIndex }.distinct().size == it.inventorySlots.size &&
                it.inventorySlots.all { slot -> slot.slotIndex in 0..5 && slot.level in 1..5 }
        } ?: null.also { clear() }
    }

    fun write(checkpoint: GameRecoveryCheckpoint) {
        val raw = listOf(
            checkpoint.levelId,
            checkpoint.waveNumber,
            checkpoint.endpointHealth,
            checkpoint.gold,
            checkpoint.savedAtEpochMillis,
            checkpoint.towers.joinToString(";") { "${it.type.name}:${it.level}:${it.row}:${it.column}" },
            checkpoint.inventorySlots.joinToString(";") { "${it.type.name}:${it.level}:${it.slotIndex}" },
        ).joinToString("|")
        preferences.edit()
            .putString(KEY_PAYLOAD, raw)
            .putString(KEY_CHECKSUM, checksum(raw))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun checksum(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest((SALT + raw).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FILE_NAME = "desktop_fortress_recovery_v1"
        const val KEY_PAYLOAD = "payload"
        const val KEY_CHECKSUM = "checksum"
        const val SALT = "desktop-fortress-recovery-integrity-v1"
    }
}
