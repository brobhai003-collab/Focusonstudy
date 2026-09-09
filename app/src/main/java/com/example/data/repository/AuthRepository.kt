package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.FocusLockApp
import com.example.data.model.UserProfile
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("focuslock_user_account", Context.MODE_PRIVATE)

    private val _userProfile = MutableStateFlow<UserProfile?>(loadSavedProfile())
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _currentUser = MutableStateFlow<UserProfile?>(_userProfile.value)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val repoScope = CoroutineScope(Dispatchers.IO)

    init {
        ensureFirebaseInitialized()
        ensurePromoCodesSeeded()
        val auth = getFirebaseAuth()
        auth?.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                repoScope.launch {
                    fetchUserProfileFromFirestore(user)
                }
            } else {
                if (_currentUser.value != null && prefs.getString("user_uid", null) != null) {
                    _currentUser.value = null
                    _userProfile.value = null
                    prefs.edit().clear().apply()
                }
            }
        }
    }

    private fun ensureFirebaseInitialized() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "FirebaseApp initialization error: ${e.message}", e)
        }
    }

    fun ensurePromoCodesSeeded() {
        val firestore = getFirestore() ?: return
        repoScope.launch {
            try {
                val sampleCodes = listOf("DPRO-7X9K2M", "DEDICATIONPRO")
                for (code in sampleCodes) {
                    val docRef = firestore.collection("promoCodes").document(code)
                    val snapshot = docRef.get().awaitTask()
                    if (!snapshot.exists()) {
                        docRef.set(mapOf("used" to false))
                        Log.d("AuthRepository", "Seeded promo code $code to Firestore")
                    }
                }
            } catch (e: Exception) {
                Log.d("AuthRepository", "Promo codes seed check: ${e.message}")
            }
        }
    }

    private fun getFirebaseAuth(): FirebaseAuth? {
        ensureFirebaseInitialized()
        return try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Failed to obtain FirebaseAuth instance", e)
            null
        }
    }

    private fun getFirestore(): FirebaseFirestore? {
        ensureFirebaseInitialized()
        return try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Failed to obtain FirebaseFirestore instance", e)
            null
        }
    }

    private fun loadSavedProfile(): UserProfile? {
        val uid = prefs.getString("user_uid", null) ?: return null
        val email = prefs.getString("user_email", "") ?: ""
        val displayName = prefs.getString("user_name", "Focus Warrior") ?: "Focus Warrior"
        val photoUrl = prefs.getString("user_photo_url", null)
        val streak = prefs.getInt("user_streak", 1)
        val totalMinutes = prefs.getLong("user_total_minutes", 0L)
        val sessions = prefs.getInt("user_sessions", 0)
        val isPro = prefs.getBoolean("user_is_pro", false)
        val lastSync = prefs.getLong("user_last_sync", System.currentTimeMillis())
        val expiresAt = if (prefs.contains("user_premium_expires_at")) prefs.getLong("user_premium_expires_at", 0L) else null

        val effectiveIsPro = if (expiresAt != null && expiresAt > 0L && expiresAt < System.currentTimeMillis()) {
            prefs.edit().putBoolean("user_is_pro", false).apply()
            try {
                FocusLockApp.instance.preferencesRepository.setProUser(false)
            } catch (e: Exception) {
                // Non-blocking
            }
            false
        } else {
            isPro
        }

        return UserProfile(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            streak = streak,
            totalFocusMinutes = totalMinutes,
            sessionsCompleted = sessions,
            isPro = effectiveIsPro,
            lastSyncTimestamp = lastSync,
            premiumExpiresAt = expiresAt
        )
    }

    suspend fun signInWithEmail(email: String, pass: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        _authError.value = null
        val cleanEmail = email.trim()
        val cleanPass = pass.trim()

        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            val err = "Please enter a valid email address."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }
        if (cleanPass.length < 6) {
            val err = "Password must be at least 6 characters."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }

        val auth = getFirebaseAuth()
        if (auth == null) {
            val err = "Firebase service unavailable. Please check Google Play Services."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }

        _isSyncing.value = true
        try {
            val authResult = auth.signInWithEmailAndPassword(cleanEmail, cleanPass).awaitTask()
            val user = authResult.user ?: throw Exception("User authentication failed.")
            val profile = fetchUserProfileFromFirestore(user)
            Result.success(profile)
        } catch (e: Exception) {
            val mappedErr = mapAuthException(e)
            Log.e("AuthRepository", "Firebase sign-in error: $mappedErr", e)
            _authError.value = mappedErr
            Result.failure(Exception(mappedErr))
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun signUpWithEmail(email: String, pass: String, name: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        _authError.value = null
        val cleanEmail = email.trim()
        val cleanPass = pass.trim()
        val cleanName = name.trim().ifEmpty { cleanEmail.substringBefore("@").replace(".", " ").capitalizeWords() }

        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            val err = "Please enter a valid email address."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }
        if (cleanPass.length < 6) {
            val err = "Password must be at least 6 characters."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }

        val auth = getFirebaseAuth()
        if (auth == null) {
            val err = "Firebase service unavailable. Please check Google Play Services."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }

        _isSyncing.value = true
        try {
            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, cleanPass).awaitTask()
            val user = authResult.user ?: throw Exception("User registration failed.")

            try {
                val updateReq = UserProfileChangeRequest.Builder()
                    .setDisplayName(cleanName)
                    .build()
                user.updateProfile(updateReq).awaitTask()
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed to update Firebase display name: ${e.message}")
            }

            val localStreak = try {
                FocusLockApp.instance.preferencesRepository.currentStreak.value
            } catch (e: Exception) {
                prefs.getInt("user_streak", 1)
            }
            val localIsPro = try {
                FocusLockApp.instance.preferencesRepository.isProUser.value
            } catch (e: Exception) {
                prefs.getBoolean("user_is_pro", false)
            }

            val profile = UserProfile(
                uid = user.uid,
                email = cleanEmail,
                displayName = cleanName,
                photoUrl = null,
                streak = maxOf(localStreak, 1),
                totalFocusMinutes = 0L,
                sessionsCompleted = 0,
                isPro = localIsPro,
                lastSyncTimestamp = System.currentTimeMillis()
            )

            val firestore = getFirestore()
            if (firestore != null) {
                val userMap = hashMapOf<String, Any>(
                    "uid" to profile.uid,
                    "email" to profile.email,
                    "displayName" to profile.displayName,
                    "streak" to profile.streak,
                    "totalFocusMinutes" to profile.totalFocusMinutes,
                    "sessionsCompleted" to profile.sessionsCompleted,
                    "isPro" to profile.isPro,
                    "lastSyncTimestamp" to profile.lastSyncTimestamp,
                    "createdAt" to System.currentTimeMillis()
                )
                firestore.collection("users").document(user.uid).set(userMap).awaitTask()
            }

            saveProfile(profile)
            Result.success(profile)
        } catch (e: Exception) {
            val mappedErr = mapAuthException(e)
            Log.e("AuthRepository", "Firebase sign-up error: $mappedErr", e)
            _authError.value = mappedErr
            Result.failure(Exception(mappedErr))
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        _authError.value = null
        val auth = getFirebaseAuth()
        if (auth == null) {
            val err = "Firebase services not initialized."
            _authError.value = err
            return@withContext Result.failure(Exception(err))
        }

        _isSyncing.value = true
        return@withContext try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).awaitTask()
            val user = authResult.user ?: throw Exception("Google sign in returned empty user profile.")
            val profile = fetchUserProfileFromFirestore(user)
            Result.success(profile)
        } catch (e: Exception) {
            val err = mapAuthException(e)
            Log.e("AuthRepository", "Google sign in error: $err", e)
            _authError.value = err
            Result.failure(Exception(err))
        } finally {
            _isSyncing.value = false
        }
    }

    fun signOut() {
        try {
            getFirebaseAuth()?.signOut()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Error signing out of Firebase: ${e.message}", e)
        }
        prefs.edit().clear().apply()
        _currentUser.value = null
        _userProfile.value = null
    }

    private fun saveProfile(profile: UserProfile) {
        val editor = prefs.edit()
            .putString("user_uid", profile.uid)
            .putString("user_email", profile.email)
            .putString("user_name", profile.displayName)
            .putString("user_photo_url", profile.photoUrl)
            .putInt("user_streak", profile.streak)
            .putLong("user_total_minutes", profile.totalFocusMinutes)
            .putInt("user_sessions", profile.sessionsCompleted)
            .putBoolean("user_is_pro", profile.isPro)
            .putLong("user_last_sync", profile.lastSyncTimestamp)

        if (profile.premiumExpiresAt != null) {
            editor.putLong("user_premium_expires_at", profile.premiumExpiresAt)
        } else {
            editor.remove("user_premium_expires_at")
        }
        editor.apply()

        _currentUser.value = profile
        _userProfile.value = profile
    }

    private suspend fun fetchUserProfileFromFirestore(user: FirebaseUser): UserProfile {
        val firestore = getFirestore()
        val cleanEmail = user.email ?: ""
        val defaultName = user.displayName ?: cleanEmail.substringBefore("@").replace(".", " ").capitalizeWords().ifEmpty { "Focus Warrior" }

        var profile = UserProfile(
            uid = user.uid,
            email = cleanEmail,
            displayName = defaultName,
            photoUrl = user.photoUrl?.toString(),
            streak = prefs.getInt("user_streak", 1),
            totalFocusMinutes = prefs.getLong("user_total_minutes", 0L),
            sessionsCompleted = prefs.getInt("user_sessions", 0),
            isPro = prefs.getBoolean("user_is_pro", false),
            lastSyncTimestamp = System.currentTimeMillis()
        )

        if (firestore != null) {
            try {
                val doc = firestore.collection("users").document(user.uid).get().awaitTask()
                if (doc.exists()) {
                    val remoteStreak = (doc.getLong("streak") ?: 0L).toInt()
                    val remoteMinutes = doc.getLong("totalFocusMinutes") ?: 0L
                    val remoteSessions = (doc.getLong("sessionsCompleted") ?: 0L).toInt()
                    val remoteExpiresAt = doc.getLong("premiumExpiresAt")
                    val now = System.currentTimeMillis()

                    // Requirement 4: check if premiumExpiresAt exists and is in the past
                    val isExpired = remoteExpiresAt != null && remoteExpiresAt < now
                    val remoteIsPro = if (isExpired) {
                        try {
                            firestore.collection("users").document(user.uid).update(
                                mapOf("isPro" to false)
                            ).awaitTask()
                        } catch (e: Exception) {
                            firestore.collection("users").document(user.uid).set(
                                mapOf("isPro" to false),
                                SetOptions.merge()
                            ).awaitTask()
                        }
                        prefs.edit().putBoolean("user_is_pro", false).apply()
                        try {
                            FocusLockApp.instance.preferencesRepository.setProUser(false)
                        } catch (e: Exception) {
                            Log.w("AuthRepository", "Failed to update preferencesRepository: ${e.message}")
                        }
                        false
                    } else {
                        doc.getBoolean("isPro") ?: false
                    }

                    val remoteName = doc.getString("displayName") ?: defaultName

                    val localStreak = prefs.getInt("user_streak", 0)
                    val localMinutes = prefs.getLong("user_total_minutes", 0L)
                    val localSessions = prefs.getInt("user_sessions", 0)
                    val localIsPro = if (isExpired) false else prefs.getBoolean("user_is_pro", false)

                    val mergedStreak = maxOf(remoteStreak, localStreak)
                    val mergedMinutes = maxOf(remoteMinutes, localMinutes)
                    val mergedSessions = maxOf(remoteSessions, localSessions)
                    val mergedIsPro = if (isExpired) false else (remoteIsPro || localIsPro)

                    profile = UserProfile(
                        uid = user.uid,
                        email = cleanEmail,
                        displayName = remoteName,
                        photoUrl = user.photoUrl?.toString(),
                        streak = mergedStreak,
                        totalFocusMinutes = mergedMinutes,
                        sessionsCompleted = mergedSessions,
                        isPro = mergedIsPro,
                        lastSyncTimestamp = doc.getLong("lastSyncTimestamp") ?: System.currentTimeMillis(),
                        premiumExpiresAt = remoteExpiresAt
                    )

                    if (!isExpired && (localStreak > remoteStreak || localMinutes > remoteMinutes || localSessions > remoteSessions || (localIsPro && !remoteIsPro))) {
                        val updateMap = hashMapOf<String, Any>(
                            "streak" to mergedStreak,
                            "totalFocusMinutes" to mergedMinutes,
                            "sessionsCompleted" to mergedSessions,
                            "isPro" to mergedIsPro,
                            "lastSyncTimestamp" to System.currentTimeMillis()
                        )
                        firestore.collection("users").document(user.uid).set(updateMap, SetOptions.merge()).awaitTask()
                    }
                } else {
                    val userMap = hashMapOf<String, Any>(
                        "uid" to profile.uid,
                        "email" to profile.email,
                        "displayName" to profile.displayName,
                        "streak" to profile.streak,
                        "totalFocusMinutes" to profile.totalFocusMinutes,
                        "sessionsCompleted" to profile.sessionsCompleted,
                        "isPro" to profile.isPro,
                        "lastSyncTimestamp" to profile.lastSyncTimestamp,
                        "createdAt" to System.currentTimeMillis()
                    )
                    firestore.collection("users").document(user.uid).set(userMap).awaitTask()
                }
            } catch (e: Exception) {
                Log.e("AuthRepository", "Error fetching/updating Firestore document: ${e.message}", e)
            }
        }

        saveProfile(profile)

        if (profile.isPro) {
            try {
                FocusLockApp.instance.preferencesRepository.setProUser(true)
            } catch (e: Exception) {
                // Non-blocking
            }
        }

        return profile
    }

    /**
     * Requirement 2 & 3:
     * Firestore-based single-use promo code redemption system with 28-day expiry.
     * Runs in a Firestore transaction to prevent double-redemption.
     */
    suspend fun redeemPromoCode(rawCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase()
        if (code.isEmpty()) {
            return@withContext Result.failure(Exception("Invalid or already used code"))
        }

        ensureFirebaseInitialized()
        val auth = getFirebaseAuth()
        val firestore = getFirestore()
        if (firestore == null || auth == null) {
            return@withContext Result.failure(Exception("Firebase services not available"))
        }

        // Ensure we have an authenticated user UID
        var currentUser = auth.currentUser
        if (currentUser == null) {
            try {
                val authResult = auth.signInAnonymously().awaitTask()
                currentUser = authResult.user
            } catch (e: Exception) {
                Log.e("AuthRepository", "Failed anonymous sign in for promo code: ${e.message}", e)
            }
        }

        val uid = currentUser?.uid ?: return@withContext Result.failure(
            Exception("Authentication required to redeem promo code. Please sign in.")
        )

        val promoDocRef = firestore.collection("promoCodes").document(code)
        val userDocRef = firestore.collection("users").document(uid)
        val expiryMillis = System.currentTimeMillis() + (28L * 24L * 60L * 60L * 1000L) // 28 days

        try {
            firestore.runTransaction { transaction ->
                // (a) reads the document with that code
                val promoSnapshot = transaction.get(promoDocRef)

                // (b) if it doesn't exist or "used" is already true, show error "Invalid or already used code"
                if (!promoSnapshot.exists()) {
                    throw Exception("Invalid or already used code")
                }

                val isAlreadyUsed = promoSnapshot.getBoolean("used") ?: false
                if (isAlreadyUsed) {
                    throw Exception("Invalid or already used code")
                }

                // (c) if valid, set "used" = true, "usedByUserId" = current Firebase Auth user's UID, "usedAt" = server timestamp
                transaction.update(
                    promoDocRef,
                    mapOf(
                        "used" to true,
                        "usedByUserId" to uid,
                        "usedAt" to FieldValue.serverTimestamp()
                    )
                )

                // Requirement 3: write to user's own document in "users" collection:
                // "isPro" = true, "premiumExpiresAt" = server timestamp + 28 days
                transaction.set(
                    userDocRef,
                    mapOf(
                        "isPro" to true,
                        "premiumExpiresAt" to expiryMillis,
                        "uid" to uid
                    ),
                    SetOptions.merge()
                )
            }.awaitTask()

            // Update local persistence
            prefs.edit()
                .putBoolean("user_is_pro", true)
                .putLong("user_premium_expires_at", expiryMillis)
                .apply()

            val currentProfile = _currentUser.value ?: loadSavedProfile()
            if (currentProfile != null) {
                val updated = currentProfile.copy(
                    isPro = true,
                    premiumExpiresAt = expiryMillis,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
                saveProfile(updated)
            }

            try {
                FocusLockApp.instance.preferencesRepository.setProUser(true)
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed to set pro in preferencesRepository: ${e.message}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            val msg = if (e.message?.contains("Invalid or already used code") == true) {
                "Invalid or already used code"
            } else {
                e.localizedMessage ?: "Invalid or already used code"
            }
            Result.failure(Exception(msg))
        }
    }

    suspend fun syncStats(streak: Int, totalMinutes: Long, sessions: Int, isPro: Boolean) = withContext(Dispatchers.IO) {
        val auth = getFirebaseAuth()
        val fbUser = auth?.currentUser
        val current = _currentUser.value ?: loadSavedProfile()

        val updatedStreak = if (current != null) maxOf(streak, current.streak) else streak
        val updatedMinutes = if (current != null) {
            if (sessions == 1 && totalMinutes > 0) current.totalFocusMinutes + totalMinutes
            else maxOf(totalMinutes, current.totalFocusMinutes)
        } else totalMinutes

        val updatedSessions = if (current != null) {
            if (sessions == 1 && totalMinutes > 0) current.sessionsCompleted + 1
            else maxOf(sessions, current.sessionsCompleted)
        } else sessions

        val updatedIsPro = isPro || (current?.isPro == true)

        val updatedProfile = UserProfile(
            uid = fbUser?.uid ?: current?.uid ?: "local_warrior",
            email = fbUser?.email ?: current?.email ?: "",
            displayName = fbUser?.displayName ?: current?.displayName ?: "Focus Warrior",
            photoUrl = fbUser?.photoUrl?.toString() ?: current?.photoUrl,
            streak = updatedStreak,
            totalFocusMinutes = updatedMinutes,
            sessionsCompleted = updatedSessions,
            isPro = updatedIsPro,
            lastSyncTimestamp = System.currentTimeMillis(),
            premiumExpiresAt = current?.premiumExpiresAt
        )

        saveProfile(updatedProfile)

        if (fbUser != null) {
            _isSyncing.value = true
            try {
                val firestore = getFirestore()
                if (firestore != null) {
                    val data = hashMapOf<String, Any>(
                        "uid" to fbUser.uid,
                        "email" to (fbUser.email ?: updatedProfile.email),
                        "displayName" to (fbUser.displayName ?: updatedProfile.displayName),
                        "streak" to updatedStreak,
                        "totalFocusMinutes" to updatedMinutes,
                        "sessionsCompleted" to updatedSessions,
                        "isPro" to updatedIsPro,
                        "lastSyncTimestamp" to System.currentTimeMillis()
                    )
                    firestore.collection("users").document(fbUser.uid).set(data, SetOptions.merge()).awaitTask()
                    Log.d("AuthRepository", "Synced stats successfully to Firestore for user ${fbUser.uid}")
                }
            } catch (e: Exception) {
                Log.e("AuthRepository", "Failed to sync stats to Firestore: ${e.message}", e)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearError() {
        _authError.value = null
    }

    private fun mapAuthException(e: Exception): String {
        return when (e) {
            is FirebaseAuthInvalidUserException -> "No account found with this email. Please create an account first."
            is FirebaseAuthInvalidCredentialsException -> "Incorrect email or password. Please check your credentials."
            is FirebaseAuthUserCollisionException -> "An account already exists with this email. Please sign in instead."
            is FirebaseAuthWeakPasswordException -> "Password is too weak. Please use at least 6 characters."
            is FirebaseNetworkException -> "Network connection failed. Please check your internet connection."
            is FirebaseAuthException -> e.localizedMessage ?: "Authentication failed."
            else -> e.localizedMessage ?: "Operation failed. Please try again."
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) {
                cont.resume(result)
            }
        }
        addOnFailureListener { exception ->
            if (cont.isActive) {
                cont.resumeWithException(exception)
            }
        }
        addOnCanceledListener {
            if (cont.isActive) {
                cont.cancel()
            }
        }
    }
}
