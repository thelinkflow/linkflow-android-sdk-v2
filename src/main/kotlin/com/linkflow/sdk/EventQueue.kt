package com.linkflow.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable, bounded queue for events that could not be delivered.
 *
 * Events used to be fired once and dropped on any failure, silently losing
 * conversions and revenue whenever the network was unavailable. Entries persist
 * across process death and are flushed on the next launch or foreground.
 *
 * Each entry carries a client-generated `eventId`; the server treats it as an
 * idempotency key, so a retry that actually did land the first time does not
 * double-count revenue.
 *
 * Backed by SharedPreferences rather than a database to keep the SDK
 * dependency-free — the volume involved (bounded at [MAX_ENTRIES]) does not
 * justify pulling Room into every host app.
 */
internal class EventQueue(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    companion object {
        private const val PREFS = "linkflow_event_queue"
        private const val KEY = "pending_events"

        /** Oldest entries are dropped beyond this, so a long offline period cannot grow without bound. */
        const val MAX_ENTRIES = 500
    }

    /** Adds an event, returning the payload actually queued (including its id). */
    fun enqueue(payload: JSONObject): JSONObject = synchronized(lock) {
        if (!payload.has("eventId")) {
            payload.put("eventId", UUID.randomUUID().toString())
        }

        val entries = read()
        entries.add(payload)

        // Drop from the front: the oldest events are the least useful.
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)

        write(entries)
        payload
    }

    /** Returns queued events without removing them. */
    fun peekAll(): List<JSONObject> = synchronized(lock) { read() }

    /** Removes a delivered event by its client-generated id. */
    fun remove(eventId: String) = synchronized(lock) {
        val remaining = read().filter { it.optString("eventId") != eventId }
        write(remaining.toMutableList())
    }

    fun size(): Int = synchronized(lock) { read().size }

    fun clear() = synchronized(lock) { prefs.edit().remove(KEY).apply() }

    private fun read(): MutableList<JSONObject> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { array.getJSONObject(it) }
        } catch (e: Exception) {
            // Corrupt queue is not worth crashing the host app over.
            prefs.edit().remove(KEY).apply()
            mutableListOf()
        }
    }

    private fun write(entries: MutableList<JSONObject>) {
        val array = JSONArray()
        entries.forEach { array.put(it) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
