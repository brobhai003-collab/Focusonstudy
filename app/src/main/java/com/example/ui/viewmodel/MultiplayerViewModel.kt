package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.FocusRoom
import com.example.data.model.LeaderboardUser
import com.example.data.repository.FirebaseMultiplayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MultiplayerViewModel(application: Application) : AndroidViewModel(application) {

    private val firebaseRepo = FirebaseMultiplayerRepository()

    val rooms: StateFlow<List<FocusRoom>> = firebaseRepo.getRoomsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), firebaseRepo.getFallbackRooms())

    val leaderboard: StateFlow<List<LeaderboardUser>> = firebaseRepo.getLeaderboardFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), firebaseRepo.getFallbackLeaderboard())

    private val _activeJoinedRoom = MutableStateFlow<FocusRoom?>(null)
    val activeJoinedRoom: StateFlow<FocusRoom?> = _activeJoinedRoom.asStateFlow()

    private val _cheerMessages = MutableStateFlow<List<String>>(emptyList())
    val cheerMessages: StateFlow<List<String>> = _cheerMessages.asStateFlow()

    fun joinRoom(room: FocusRoom) {
        _activeJoinedRoom.value = room
        _cheerMessages.value = listOf(
            "👋 Welcome to ${room.name}!",
            "🔥 ${room.hostName} says: Let's lock in and crush this session!"
        )
        firebaseRepo.joinRoom(room.id, "You")
    }

    fun leaveRoom() {
        _activeJoinedRoom.value?.let {
            firebaseRepo.leaveRoom(it.id, "You")
        }
        _activeJoinedRoom.value = null
    }

    fun sendCheer(emoji: String) {
        val msg = "You sent: $emoji Keep going everyone!"
        val current = _cheerMessages.value.toMutableList()
        current.add(msg)
        _cheerMessages.value = current

        _activeJoinedRoom.value?.let {
            firebaseRepo.sendCheer(it.id, msg)
        }
    }

    fun createRoom(name: String, topic: String, durationMins: Int, isPro: Boolean) {
        val newRoom = FocusRoom(
            id = "room_${System.currentTimeMillis()}",
            name = name,
            hostName = "You",
            topic = topic,
            participantCount = 1,
            durationMinutes = durationMins,
            isProOnly = isPro,
            activeParticipants = listOf("You")
        )
        firebaseRepo.createRoom(newRoom)
        joinRoom(newRoom)
    }

    fun recordCompletedFocus(minutes: Int) {
        firebaseRepo.updateMyScore("You (FocusMaster)", minutes, 1)
    }
}
