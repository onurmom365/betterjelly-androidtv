package org.jellyfin.androidtv.ui.seerr

/** Small, per-account memory cache. Mutations and explicit status checks always read fresh data. */
internal class SeerrTimedCache<K, V>(private val capacity: Int, private val ttlNanos: Long, private val clock: () -> Long = System::nanoTime) {
	private data class Entry<V>(val value: V, val savedAt: Long)
	private val entries = LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true)
	@Synchronized fun get(key: K): V? {
		val entry = entries[key] ?: return null
		if (clock() - entry.savedAt >= ttlNanos) { entries.remove(key); return null }
		return entry.value
	}
	@Synchronized fun put(key: K, value: V) {
		entries[key] = Entry(value, clock())
		while (entries.size > capacity) entries.remove(entries.keys.first())
	}
	@Synchronized fun clear() = entries.clear()
}
