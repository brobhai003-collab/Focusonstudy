package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.UserProfile
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthRepository(private val context: Context) {

    private val auth: FirebaseAuth? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance()
            } else null
        } catch (e: Exception) {
            Log.w("AuthRepo", "Firebase Auth init failed: ${e.message}")
            null
        }
    }

    private val firestore: FirebaseFirestore? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else null
        } catch (e: Exception) {
            Log.w("AuthRepo", "Firestore init failed: ${e.message}")
            null
        }
    }

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth?.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val repoScope = CoroutineScope(Dispatchers.IO)

    init {
        auth?.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            if (user != null) {
                repoScope.launch {
                    loadCloudProfile(user.uid, user.email, user.displayName)
                }
            } else {
                _userProfile.value = null
            }
        }
        // Initial load if already signed in
        auth?.currentUser?.let { user ->
            repoScope.launch {
                loadCloudProfile(user.uid, user.email, user.displayName)
            }
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): Result<FirebaseUser> {
        _authError.value = null
        val authInstance = auth ?: return Result.failure(Exception("Firebase not configured"))
        return try {
            val result = authInstance.signInWithEmailAndPassword(email.trim(), pass.trim()).await()
            val user = result.user ?: throw Exception("User is null after sign in")
            _currentUser.value = user
            loadCloudProfile(user.uid, user.email, user.displayName)
            Result.success(user)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to sign in"
            _authError.value = msg
            Result.failure(Exception(msg))
        }
    }

    suspend fun signUpWithEmail(email: String, pass: String, name: String): Result<FirebaseUser> {
        _authError.value = null
        val authInstance = auth ?: return Result.failure(Exception("Firebase not configured"))
        return try {
            val result = authInstance.createUserWithEmailAndPassword(email.trim(), pass.trim()).await()
            val user = result.user ?: throw Exception("User creation returned null")
            
            // Set display name
            try {
                user.updateProfile(
                    UserProfileChangeRequest.Builder()
                        .setDisplayName(name.trim().ifEmpty { "Focus Warrior" })
                        .build()
                ).await()
            } catch (e: Exception) {
                Log.w("AuthRepo", "Failed to set display name: ${e.message}")
            }

            _currentUser.value = user
            val initialProfile = UserProfile(
                uid = user.uid,
                email = user.email ?: "",
                displayName = name.trim().ifEmpty { "Focus Warrior" },
                streak = 1,
                totalFocusMinutes = 0,
                sessionsCompleted = 0,
                isPro = false,
                lastSyncTimestamp = System.currentTimeMillis()
            )
            saveProfileToCloud(initialProfile)
            _userProfile.value = initialProfile
            Result.success(user)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Registration failed"
            _authError.value = msg
            Result.failure(Exception(msg))
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser> {
        _authError.value = null
        val authInstance = auth ?: return Result.failure(Exception("Firebase not configured"))
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = authInstance.signInWithCredential(credential).await()
            val user = result.user ?: throw Exception("Google sign in returned null user")
            _currentUser.value = user
            loadCloudProfile(user.uid, user.email, user.displayName)
            Result.success(user)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Google Sign-In failed"
            _authError.value = msg
            Result.failure(Exception(msg))
        }
    }

    fun signOut() {
        try {
            auth?.signOut()
            _currentUser.value = null
            _userProfile.value = null
        } catch (e: Exception) {
            Log.e("AuthRepo", "Sign out error: ${e.message}")
        }
    }

    suspend fun loadCloudProfile(uid: String, email: String?, displayName: String?): UserProfile? {
        val db = firestore ?: return null
        return try {
            _isSyncing.value = true
            val doc = db.collection("users").document(uid).get().await()
            if (doc.exists()) {
                val profile = UserProfile(
                    uid = uid,
                    email = doc.getString("email") ?: email ?: "",
                    displayName = doc.getString("displayName") ?: displayName ?: "Focus Warrior",
                    photoUrl = doc.getString("photoUrl"),
                    streak = doc.getLong("streak")?.toInt() ?: 1,
                    totalFocusMinutes = doc.getLong("totalFocusMinutes") ?: 0L,
                    sessionsCompleted = doc.getLong("sessionsCompleted")?.toInt() ?: 0,
                    isPro = doc.getBoolean("isPro") ?: false,
                    lastSyncTimestamp = doc.getLong("lastSyncTimestamp") ?: System.currentTimeMillis()
                )
                _userProfile.value = profile
                profile
            } else {
                val newProfile = UserProfile(
                    uid = uid,
                    email = email ?: "",
                    displayName = displayName ?: "Focus Warrior",
                    streak = 1,
                    totalFocusMinutes = 0L,
                    sessionsCompleted = 0,
                    isPro = false,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
                saveProfileToCloud(newProfile)
                _userProfile.value = newProfile
                newProfile
            }
        } catch (e: Exception) {
            Log.e("AuthRepo", "Error loading profile: ${e.message}")
            null
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun saveProfileToCloud(profile: UserProfile): Boolean {
        val db = firestore ?: return false
        return try {
            _isSyncing.value = true
            val data = mapOf(
                "uid" to profile.uid,
                "email" to profile.email,
                "displayName" to profile.displayName,
                "streak" to profile.streak,
                "totalFocusMinutes" to profile.totalFocusMinutes,
                "sessionsCompleted" to profile.sessionsCompleted,
                "isPro" to profile.isPro,
                "lastSyncTimestamp" to System.currentTimeMillis()
            )
            db.collection("users").document(profile.uid)
                .set(data, SetOptions.merge())
                .await()
            _userProfile.value = profile.copy(lastSyncTimestamp = System.currentTimeMillis())
            true
        } catch (e: Exception) {
            Log.e("AuthRepo", "Error saving profile: ${e.message}")
            false
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun syncStats(streak: Int, totalMinutes: Long, sessions: Int, isPro: Boolean) {
        val current = _currentUser.value ?: return
        val existing = _userProfile.value
        val updated = UserProfile(
            uid = current.uid,
            email = current.email ?: "",
            displayName = current.displayName ?: existing?.displayName ?: "Focus Warrior",
            streak = maxOf(streak, existing?.streak ?: 1),
            totalFocusMinutes = maxOf(totalMinutes, existing?.totalFocusMinutes ?: 0L),
            sessionsCompleted = maxOf(sessions, existing?.sessionsCompleted ?: 0),
            isPro = isPro || (existing?.isPro ?: false),
            lastSyncTimestamp = System.currentTimeMillis()
        )
        saveProfileToCloud(updated)
    }

    fun clearError() {
        _authError.value = null
    }
}
