package io.livekit.android.sample

@kotlinx.serialization.Serializable
data class ConnectionPreset(
    val id: String,
    val label: String,
    val url: String,
    val token: String,
    val useQuicSignal: Boolean = false,
    val serverHost: String = "",
    val caCertPem: String = "",
)
