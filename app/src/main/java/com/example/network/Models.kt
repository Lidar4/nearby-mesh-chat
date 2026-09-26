package com.example.network

data class DiscoveredDevice(
    val id: String, // Device ID
    val name: String, // Display Name
    val ipAddress: String, // IP Address to connect to
    val port: Int, // TCP Port
    val status: ConnectionState = ConnectionState.AVAILABLE
)

enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    WAITING_FOR_ACCEPT,
    CONNECTED,
    RECONNECTING,
    REJECTED,
    ERROR,
    AVAILABLE
}
