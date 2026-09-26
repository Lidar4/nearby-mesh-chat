package com.example.network

import android.util.Log
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class RouteEntry(
    val destinationId: String,
    val nextHopId: String,
    val hopCount: Int,
    val timestamp: Long
)

class MeshRouter(private val localDeviceId: String) {

    private val TAG = "MeshRouter"
    
    // Route Table: destinationId -> RouteEntry
    val routeTable = ConcurrentHashMap<String, RouteEntry>()

    // Bounded duplicate cache to prevent routing loops and duplicate packet overhead
    private val duplicateCache = Collections.synchronizedSet(LinkedHashSet<String>())
    private val MAX_CACHE_SIZE = 1000

    /**
     * Records a message ID in our duplicate detector to avoid re-processing or re-forwarding
     * Returns true if this is a NEW message (not seen before), false if it is a duplicate.
     */
    fun checkAndRecordMessage(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        synchronized(duplicateCache) {
            if (duplicateCache.contains(messageId)) {
                return false
            }
            duplicateCache.add(messageId)
            if (duplicateCache.size > MAX_CACHE_SIZE) {
                val iterator = duplicateCache.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            return true
        }
    }

    /**
     * Learns a route to the sender of any received packet.
     * We map senderId -> previousHopId as the next hop with hopCount derived from (MAX_HOPS - ttl + 1)
     */
    fun learnRoute(senderId: String, previousHopId: String, ttl: Int, maxHops: Int) {
        if (senderId == localDeviceId) return
        val hops = (maxHops - ttl + 1).coerceAtLeast(1)
        
        val existingRoute = routeTable[senderId]
        if (existingRoute == null || existingRoute.hopCount > hops || (System.currentTimeMillis() - existingRoute.timestamp) > 300000) {
            val entry = RouteEntry(
                destinationId = senderId,
                nextHopId = previousHopId,
                hopCount = hops,
                timestamp = System.currentTimeMillis()
            )
            routeTable[senderId] = entry
            Log.d(TAG, "Learned route: $senderId goes via next-hop $previousHopId (hops: $hops)")
        }
    }

    /**
     * Retrieves the best next-hop for reaching a given destination.
     * Returns the next-hop node ID if a route exists, or null otherwise.
     */
    fun getNextHopFor(destinationId: String): String? {
        val entry = routeTable[destinationId]
        if (entry != null) {
            // Check if route has expired (e.g. 5 minutes)
            if (System.currentTimeMillis() - entry.timestamp < 300000) {
                return entry.nextHopId
            } else {
                routeTable.remove(destinationId)
            }
        }
        return null
    }

    /**
     * Returns the list of all active routes for UI reporting
     */
    fun getActiveRoutesList(): List<RouteEntry> {
        // Prune expired routes
        val now = System.currentTimeMillis()
        val expired = routeTable.filter { now - it.value.timestamp >= 300000 }.keys
        expired.forEach { routeTable.remove(it) }
        return routeTable.values.toList()
    }
}
