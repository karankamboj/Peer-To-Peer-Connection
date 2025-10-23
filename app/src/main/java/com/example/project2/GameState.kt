package com.example.project2

import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val type: String = "hello",
    val roomId: String = "ROOM-1",
    val turn: Int = 0,
    val payload: Map<String, String> = mapOf("msg" to "Hi via Nearby!")
)
