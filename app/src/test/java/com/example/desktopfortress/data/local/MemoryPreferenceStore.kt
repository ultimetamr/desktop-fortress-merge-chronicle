package com.example.desktopfortress.data.local

internal class MemoryPreferenceStore : PreferenceStore {
    val strings = linkedMapOf<String, String>()
    val sets = linkedMapOf<String, Set<String>>()

    override fun getString(key: String): String? = strings[key]
    override fun getStringSet(key: String): Set<String> = sets[key].orEmpty()
    override fun put(strings: Map<String, String>, sets: Map<String, Set<String>>) {
        this.strings.putAll(strings)
        sets.forEach { (key, value) -> this.sets[key] = value.toSet() }
    }

    fun tamperString(key: String, value: String) {
        strings[key] = value
    }
}
