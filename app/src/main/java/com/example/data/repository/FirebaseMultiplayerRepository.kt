package com.example.data.repository

import android.util.Log
import com.example.data.model.FocusRoom
import com.example.data.model.LeaderboardUser
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class FirebaseMultiplayerRepository {

    private val firestore: FirebaseFirestore? by lazy {
        try {
            if (FirebaseApp.getApps(com.example.FocusLockApp.instance).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("FirebaseRepo", "Firebase not initialized or google-services.json missing: ${e.message}")
            null
        }
    }

    val isFirebaseAvailable: Boolean
        get() = firestore != null

    fun getRoomsFlow(): Flow<List<FocusRoom>> = callbackFlow {
        val db = firestore
        if (db == null) {
            trySend(getFallbackRooms())
            awaitClose { }
            return@callbackFlow
        }

        var listener: ListenerRegistration? = null
        try {
            listener = db.collection("focus_rooms")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("FirebaseRepo", "Error listening to rooms: ${error.message}")
                        trySend(getFallbackRooms())
                        return@addSnapshotListener
                    }

                    if (snapshot != null && !snapshot.isEmpty) {
                        val rooms = snapshot.documents.mapNotNull { doc ->
                            try {
                                val id = doc.id
                                val name = doc.getString("name") ?: "Focus Lounge"
                                val hostName = doc.getString("hostName") ?: "Study Host"
                                val topic = doc.getString("topic") ?: "Deep work"
                                val durationMinutes = doc.getLong("durationMinutes")?.toInt() ?: 25
                                val isProOnly = doc.getBoolean("isProOnly") ?: false
                                val activeList = (doc.get("activeParticipants") as? List<*>)?.mapNotNull { it?.toString() } ?: listOf(hostName)
                                FocusRoom(
                                    id = id,
                                    name = name,
                                    hostName = hostName,
                                    topic = topic,
                                    participantCount = activeList.size,
                                    durationMinutes = durationMinutes,
                                    isProOnly = isProOnly,
                                    activeParticipants = activeList
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        trySend(rooms)
                    } else {
                        seedDefaultRooms()
                        trySend(getFallbackRooms())
                    }
                }
        } catch (e: Exception) {
            trySend(getFallbackRooms())
        }

        awaitClose {
            listener?.remove()
        }
    }

    fun getLeaderboardFlow(): Flow<List<LeaderboardUser>> = callbackFlow {
        val db = firestore
        if (db == null) {
            trySend(getFallbackLeaderboard())
            awaitClose { }
            return@callbackFlow
        }

        var listener: ListenerRegistration? = null
        try {
            listener = db.collection("focus_leaderboard")
                .orderBy("focusMinutesToday", Query.Direction.DESCENDING)
                .limit(20)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(getFallbackLeaderboard())
                        return@addSnapshotListener
                    }

                    if (snapshot != null && !snapshot.isEmpty) {
                        val users = snapshot.documents.mapIndexedNotNull { index, doc ->
                            try {
                                val username = doc.getString("username") ?: "Focus User"
                                val focusMinutesToday = doc.getLong("focusMinutesToday")?.toInt() ?: 0
                                val focusMinutesWeek = doc.getLong("focusMinutesWeek")?.toInt() ?: (focusMinutesToday * 5)
                                val streak = doc.getLong("streakDays")?.toInt() ?: 1
                                val badge = doc.getString("badgeTitle") ?: "Focus Apprentice"
                                val avatar = doc.getString("avatarEmoji") ?: "🧘"
                                val isCurrentUser = doc.getBoolean("isCurrentUser") ?: (username.contains("You", ignoreCase = true))

                                LeaderboardUser(
                                    rank = index + 1,
                                    username = username,
                                    focusMinutesToday = focusMinutesToday,
                                    focusMinutesWeek = focusMinutesWeek,
                                    streakDays = streak,
                                    badgeTitle = badge,
                                    avatarEmoji = avatar,
                                    isCurrentUser = isCurrentUser
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        trySend(users)
                    } else {
                        seedDefaultLeaderboard()
                        trySend(getFallbackLeaderboard())
                    }
                }
        } catch (e: Exception) {
            trySend(getFallbackLeaderboard())
        }

        awaitClose {
            listener?.remove()
        }
    }

    fun createRoom(room: FocusRoom) {
        val db = firestore ?: return
        val roomData = hashMapOf(
            "name" to room.name,
            "hostName" to room.hostName,
            "topic" to room.topic,
            "durationMinutes" to room.durationMinutes,
            "isProOnly" to room.isProOnly,
            "activeParticipants" to listOf(room.hostName),
            "createdAt" to System.currentTimeMillis()
        )
        db.collection("focus_rooms").document(room.id).set(roomData)
    }

    fun joinRoom(roomId: String, userName: String) {
        val db = firestore ?: return
        db.collection("focus_rooms").document(roomId)
            .update("activeParticipants", FieldValue.arrayUnion(userName))
    }

    fun leaveRoom(roomId: String, userName: String) {
        val db = firestore ?: return
        db.collection("focus_rooms").document(roomId)
            .update("activeParticipants", FieldValue.arrayRemove(userName))
    }

    fun sendCheer(roomId: String, message: String) {
        val db = firestore ?: return
        db.collection("focus_rooms").document(roomId)
            .update("recentCheers", FieldValue.arrayUnion(message))
    }

    fun updateMyScore(username: String, additionalMinutes: Int, streak: Int) {
        val db = firestore ?: return
        val userDoc = db.collection("focus_leaderboard").document(username)
        val data = hashMapOf(
            "username" to username,
            "focusMinutesToday" to FieldValue.increment(additionalMinutes.toLong()),
            "focusMinutesWeek" to FieldValue.increment(additionalMinutes.toLong()),
            "streakDays" to streak,
            "isCurrentUser" to true,
            "updatedAt" to System.currentTimeMillis()
        )
        userDoc.set(data, com.google.firebase.firestore.SetOptions.merge())
    }

    private fun seedDefaultRooms() {
        val db = firestore ?: return
        getFallbackRooms().forEach { room ->
            db.collection("focus_rooms").document(room.id).set(
                hashMapOf(
                    "name" to room.name,
                    "hostName" to room.hostName,
                    "topic" to room.topic,
                    "durationMinutes" to room.durationMinutes,
                    "isProOnly" to room.isProOnly,
                    "activeParticipants" to room.activeParticipants,
                    "createdAt" to System.currentTimeMillis()
                )
            )
        }
    }

    private fun seedDefaultLeaderboard() {
        val db = firestore ?: return
        getFallbackLeaderboard().forEach { user ->
            db.collection("focus_leaderboard").document(user.username).set(
                hashMapOf(
                    "username" to user.username,
                    "focusMinutesToday" to user.focusMinutesToday,
                    "focusMinutesWeek" to user.focusMinutesWeek,
                    "streakDays" to user.streakDays,
                    "badgeTitle" to user.badgeTitle,
                    "avatarEmoji" to user.avatarEmoji,
                    "isCurrentUser" to user.isCurrentUser
                )
            )
        }
    }

    fun getFallbackRooms(): List<FocusRoom> {
        return listOf(
            FocusRoom(
                id = "room_1",
                name = "Silent Study Lounge ☕",
                hostName = "Sarah_K",
                topic = "Deep coding & thesis prep",
                participantCount = 14,
                durationMinutes = 50,
                isProOnly = false,
                activeParticipants = listOf("Sarah_K", "Alex_Dev", "Kenji", "Priya99", "You")
            ),
            FocusRoom(
                id = "room_2",
                name = "Night Owls Focus 🌙",
                hostName = "Vikram_M",
                topic = "Exam Cram & Revision",
                participantCount = 8,
                durationMinutes = 25,
                isProOnly = false,
                activeParticipants = listOf("Vikram_M", "Elena_R", "Sam_T")
            ),
            FocusRoom(
                id = "room_3",
                name = "Pro Pomodoro Sprints ⚡",
                hostName = "Marcus_Zen",
                topic = "Strict Mode 50/10 Cycles",
                participantCount = 22,
                durationMinutes = 50,
                isProOnly = true,
                activeParticipants = listOf("Marcus_Zen", "Devon_B", "Chloe_99", "Arjun")
            ),
            FocusRoom(
                id = "room_4",
                name = "ADHD Body Doubling 🛡️",
                hostName = "Maya_Focus",
                topic = "Friendly accountability sprint",
                participantCount = 19,
                durationMinutes = 30,
                isProOnly = false,
                activeParticipants = listOf("Maya_Focus", "Tariq", "Jessica_L", "Zack")
            )
        )
    }

    fun getFallbackLeaderboard(): List<LeaderboardUser> {
        return listOf(
            LeaderboardUser(1, "Aarav Sharma", 320, 1840, 14, "Zen Grandmaster", "🧘"),
            LeaderboardUser(2, "Elena Rostova", 295, 1620, 11, "Iron Will", "🛡️"),
            LeaderboardUser(3, "Kenji Sato", 270, 1490, 9, "Laser Focus", "⚡"),
            LeaderboardUser(4, "You (FocusMaster)", 185, 940, 4, "Focus Apprentice", "🤖", isCurrentUser = true),
            LeaderboardUser(5, "Sarah Jenkins", 170, 910, 6, "Shield Bearer", "🌟"),
            LeaderboardUser(6, "David Chen", 155, 820, 3, "Sprint Runner", "🚀"),
            LeaderboardUser(7, "Priya Patel", 140, 780, 5, "Deep Thinker", "📚")
        )
    }
}
