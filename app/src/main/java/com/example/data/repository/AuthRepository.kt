package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val _accountDisabledMessage = MutableStateFlow<String?>(null)
    val accountDisabledMessage: StateFlow<String?> = _accountDisabledMessage.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val repoScope = CoroutineScope(Dispatchers.IO)
    private var periodicRefreshJob: Job? = null
    private var isForegrounded = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val tokenCheckMutex = Mutex()

    init {
        ensureFirebaseInitialized()
        ensurePromoCodesSeeded()
        registerNetworkConnectivityListener()
        val auth = getFirebaseAuth()
        val initialUser = auth?.currentUser
        if (initialUser != null) {
            // Requirement 1: On app launch, fetch that specific user's document from Firestore "users/{uid}"
            // Use ONLY that data for isPro and premiumExpiresAt.
            repoScope.launch {
                fetchUserProfileFromFirestore(initialUser)
                // Real-time account verification on launch
                checkUserTokenStatus(forceRefresh = true)
            }
        } else {
            // No user is logged in
            _currentUser.value = null
            _userProfile.value = null
            prefs.edit().clear().apply()
            try {
                FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
            } catch (e: Exception) {
                // Non-blocking
            }
        }

        auth?.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                repoScope.launch {
                    fetchUserProfileFromFirestore(user)
                }
            } else {
                // Requirement 2: Clear cached state on logout
                if (_currentUser.value != null || prefs.getString("user_uid", null) != null) {
                    _currentUser.value = null
                    _userProfile.value = null
                    prefs.edit().clear().apply()
                    try {
                        FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
                    } catch (e: Exception) {
                        // Non-blocking
                    }
                }
            }
        }

        // Real-time check via Firebase ID token listener (addIdTokenListener)
        auth?.addIdTokenListener(FirebaseAuth.IdTokenListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                repoScope.launch {
                    checkUserTokenStatus(forceRefresh = false)
                }
            } else {
                if (_currentUser.value != null || prefs.getString("user_uid", null) != null) {
                    _currentUser.value = null
                    _userProfile.value = null
                    prefs.edit().clear().apply()
                    try {
                        FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
                    } catch (e: Exception) {
                        // Non-blocking
                    }
                }
            }
        })
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

    /**
     * Requirement 1 & 2:
     * Never reuse cached state from a different account or after logout.
     * Only return cached profile if it strictly matches the current Firebase Auth user's UID.
     */
    private fun loadSavedProfile(): UserProfile? {
        val auth = getFirebaseAuth()
        val currentFirebaseUser = auth?.currentUser ?: return null
        val savedUid = prefs.getString("user_uid", null) ?: return null

        // If saved cache belongs to a different UID than current Firebase user, discard immediately
        if (savedUid != currentFirebaseUser.uid) {
            prefs.edit().clear().apply()
            return null
        }

        val email = prefs.getString("user_email", "") ?: currentFirebaseUser.email ?: ""
        val displayName = prefs.getString("user_name", "Focus Warrior") ?: "Focus Warrior"
        val photoUrl = prefs.getString("user_photo_url", null) ?: currentFirebaseUser.photoUrl?.toString()
        val streak = prefs.getInt("user_streak", 0)
        val totalMinutes = prefs.getLong("user_total_minutes", 0L)
        val sessions = prefs.getInt("user_sessions", 0)
        val isPro = prefs.getBoolean("user_is_pro", false)
        val lastSync = prefs.getLong("user_last_sync", System.currentTimeMillis())
        val expiresAt = if (prefs.contains("user_premium_expires_at")) prefs.getLong("user_premium_expires_at", 0L) else null

        val isExpired = expiresAt != null && expiresAt > 0L && expiresAt < System.currentTimeMillis()
        val effectiveIsPro = if (isExpired) {
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
            uid = savedUid,
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
            // Clear any leftover local UI state before signing in so nothing leaks from a previous session
            try {
                FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
            } catch (e: Exception) {
                // Non-blocking
            }

            val authResult = auth.signInWithEmailAndPassword(cleanEmail, cleanPass).awaitTask()
            val user = authResult.user ?: throw Exception("User authentication failed.")

            // Requirement 1 & 4: Fetch user document from Firestore "users/{uid}" as single source of truth
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
            // Requirement 2 & 3: Clear any leftover UI preferences so new account starts totally clean
            try {
                FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
            } catch (e: Exception) {
                // Non-blocking
            }

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

            // Requirement 3: New account is isPro = false by default, zero stats, no inherited state
            val profile = UserProfile(
                uid = user.uid,
                email = cleanEmail,
                displayName = cleanName,
                photoUrl = null,
                streak = 0,
                totalFocusMinutes = 0L,
                sessionsCompleted = 0,
                isPro = false,
                lastSyncTimestamp = System.currentTimeMillis(),
                premiumExpiresAt = null
            )

            val firestore = getFirestore()
            if (firestore != null) {
                val userMap = hashMapOf<String, Any?>(
                    "uid" to profile.uid,
                    "email" to profile.email,
                    "displayName" to profile.displayName,
                    "streak" to 0,
                    "totalFocusMinutes" to 0L,
                    "sessionsCompleted" to 0,
                    "isPro" to false,
                    "premiumExpiresAt" to null,
                    "lastSyncTimestamp" to profile.lastSyncTimestamp,
                    "createdAt" to System.currentTimeMillis()
                )
                firestore.collection("users").document(user.uid).set(userMap).awaitTask()
            }

            saveProfile(profile)
            try {
                FocusLockApp.instance.preferencesRepository.setProUser(false)
            } catch (e: Exception) {
                // Non-blocking
            }

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
            try {
                FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
            } catch (e: Exception) {
                // Non-blocking
            }

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

    /**
     * Requirement 2:
     * When any user logs out, clear all locally-stored UI/preference state
     * (SharedPreferences) including toggles like Strict Mode, so nothing leaks between accounts.
     * Also clear any locally cached isPro/premiumExpiresAt values.
     */
    fun signOut() {
        try {
            getFirebaseAuth()?.signOut()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Error signing out of Firebase: ${e.message}", e)
        }
        prefs.edit().clear().apply()
        _currentUser.value = null
        _userProfile.value = null

        try {
            FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Error resetting preferences on logout: ${e.message}", e)
        }
    }

    /**
     * Force-signs-out user immediately if their Firebase account is disabled or deleted.
     * Sets error banner message, cleans up local state, and stops any active focus session.
     */
    fun handleAccountDisabled(message: String = "Your account has been disabled.") {
        Log.w("AuthRepository", "Force-signing-out: Account disabled/invalid ($message)")
        _accountDisabledMessage.value = message
        _authError.value = message
        try {
            getFirebaseAuth()?.signOut()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Error during FirebaseAuth signOut: ${e.message}", e)
        }
        prefs.edit().clear().apply()
        _currentUser.value = null
        _userProfile.value = null

        try {
            FocusLockApp.instance.preferencesRepository.resetUserPreferencesOnLogout()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Error resetting preferences on disabled logout: ${e.message}", e)
        }

        try {
            com.example.service.FocusTimerService.stop(context)
        } catch (e: Exception) {
            // Non-blocking
        }
    }

    /**
     * Verifies user's token and account status against Firebase Auth backend.
     * If forceRefresh is true, requests a new ID token directly from the server.
     * If account is disabled (ERROR_USER_DISABLED / user-disabled), immediately force-signs-out.
     * Synchronized via tokenCheckMutex to prevent duplicate concurrent network calls.
     */
    suspend fun checkUserTokenStatus(forceRefresh: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val auth = getFirebaseAuth() ?: return@withContext false
        val user = auth.currentUser ?: return@withContext false

        tokenCheckMutex.withLock {
            if (_currentUser.value == null && auth.currentUser == null) {
                return@withContext false
            }

            try {
                user.getIdToken(forceRefresh).awaitTask()
                user.reload().awaitTask()
                true
            } catch (e: Exception) {
                Log.w("AuthRepository", "checkUserTokenStatus check result: ${e.message}")
                if (isUserDisabledException(e)) {
                    Log.w("AuthRepository", "User account disabled detected! Forcing signout.")
                    handleAccountDisabled("Your account has been disabled.")
                    false
                } else if (isUserNotFoundOrDeletedException(e)) {
                    Log.w("AuthRepository", "User account deleted or not found! Forcing signout.")
                    handleAccountDisabled("Your account is no longer available.")
                    false
                } else {
                    // Network or transient error: do not log out user on temporary connection drop
                    true
                }
            }
        }
    }

    /**
     * Registers a ConnectivityManager.NetworkCallback to immediately trigger
     * a token refresh check whenever internet connectivity is gained or regained
     * while the app is open. If an account was disabled while offline, this ensures
     * immediate force-sign-out the moment connectivity returns.
     */
    fun registerNetworkConnectivityListener() {
        if (networkCallback != null) return
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d("AuthRepository", "Network connection gained/regained while app is open. Triggering token refresh check.")
                    if (isForegrounded && (_currentUser.value != null || getFirebaseAuth()?.currentUser != null)) {
                        repoScope.launch {
                            checkUserTokenStatus(forceRefresh = true)
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (hasInternet && isForegrounded && (_currentUser.value != null || getFirebaseAuth()?.currentUser != null)) {
                        repoScope.launch {
                            checkUserTokenStatus(forceRefresh = true)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d("AuthRepository", "Network connection lost.")
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.d("AuthRepository", "ConnectivityManager.NetworkCallback registered successfully.")
        } catch (e: Exception) {
            Log.e("AuthRepository", "Failed to register NetworkCallback: ${e.message}", e)
        }
    }

    fun unregisterNetworkConnectivityListener() {
        val callback = networkCallback ?: return
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
            Log.d("AuthRepository", "ConnectivityManager.NetworkCallback unregistered.")
        } catch (e: Exception) {
            Log.e("AuthRepository", "Failed to unregister NetworkCallback: ${e.message}", e)
        } finally {
            networkCallback = null
        }
    }

    fun onAppForegrounded() {
        isForegrounded = true
        registerNetworkConnectivityListener()
        startPeriodicTokenRefresh()
        repoScope.launch {
            if (_currentUser.value != null || getFirebaseAuth()?.currentUser != null) {
                checkUserTokenStatus(forceRefresh = true)
            }
        }
    }

    fun onAppBackgrounded() {
        isForegrounded = false
        unregisterNetworkConnectivityListener()
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    private fun startPeriodicTokenRefresh() {
        if (periodicRefreshJob?.isActive == true) return
        periodicRefreshJob = repoScope.launch {
            while (isActive && isForegrounded) {
                // Periodic check every 2 minutes while app is in foreground
                delay(2 * 60 * 1000L)
                if (isForegrounded && (_currentUser.value != null || getFirebaseAuth()?.currentUser != null)) {
                    Log.d("AuthRepository", "Running periodic foreground token refresh check...")
                    checkUserTokenStatus(forceRefresh = true)
                }
            }
        }
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

    /**
     * Requirement 1, 4, 6:
     * PREMIUM STATE SOURCE OF TRUTH:
     * Fetches user's document from Firestore "users/{uid}" and uses ONLY that data
     * for isPro and premiumExpiresAt.
     * Never merges or keeps cached premium state from previous sessions or accounts.
     */
    private suspend fun fetchUserProfileFromFirestore(user: FirebaseUser): UserProfile {
        val firestore = getFirestore()
        val cleanEmail = user.email ?: ""
        val defaultName = user.displayName ?: cleanEmail.substringBefore("@").replace(".", " ").capitalizeWords().ifEmpty { "Focus Warrior" }
        val now = System.currentTimeMillis()

        var remoteIsPro = false
        var remoteExpiresAt: Long? = null
        var remoteStreak = 0
        var remoteMinutes = 0L
        var remoteSessions = 0
        var remoteDisplayName = defaultName
        var remoteLastSync = now

        if (firestore != null) {
            try {
                val doc = firestore.collection("users").document(user.uid).get().awaitTask()
                if (doc.exists()) {
                    remoteStreak = (doc.getLong("streak") ?: 0L).toInt()
                    remoteMinutes = doc.getLong("totalFocusMinutes") ?: 0L
                    remoteSessions = (doc.getLong("sessionsCompleted") ?: 0L).toInt()
                    remoteDisplayName = doc.getString("displayName") ?: defaultName
                    remoteLastSync = doc.getLong("lastSyncTimestamp") ?: now

                    val rawIsPro = doc.getBoolean("isPro") ?: false
                    remoteExpiresAt = doc.getLong("premiumExpiresAt")

                    // Requirement 1 & 4 & 6: Expiration check
                    if (remoteExpiresAt != null && remoteExpiresAt > 0L && remoteExpiresAt < now) {
                        remoteIsPro = false
                        try {
                            firestore.collection("users").document(user.uid).set(
                                mapOf("isPro" to false),
                                SetOptions.merge()
                            ).awaitTask()
                        } catch (e: Exception) {
                            Log.w("AuthRepository", "Failed to update expired pro state in Firestore: ${e.message}")
                        }
                    } else {
                        remoteIsPro = rawIsPro
                    }
                } else {
                    // New user document in Firestore - strictly false
                    val userMap = hashMapOf<String, Any?>(
                        "uid" to user.uid,
                        "email" to cleanEmail,
                        "displayName" to defaultName,
                        "streak" to 0,
                        "totalFocusMinutes" to 0L,
                        "sessionsCompleted" to 0,
                        "isPro" to false,
                        "premiumExpiresAt" to null,
                        "lastSyncTimestamp" to now,
                        "createdAt" to now
                    )
                    firestore.collection("users").document(user.uid).set(userMap).awaitTask()
                    remoteIsPro = false
                    remoteExpiresAt = null
                }
            } catch (e: Exception) {
                Log.e("AuthRepository", "Error fetching user document from Firestore: ${e.message}", e)
            }
        }

        val profile = UserProfile(
            uid = user.uid,
            email = cleanEmail,
            displayName = remoteDisplayName,
            photoUrl = user.photoUrl?.toString(),
            streak = remoteStreak,
            totalFocusMinutes = remoteMinutes,
            sessionsCompleted = remoteSessions,
            isPro = remoteIsPro,
            lastSyncTimestamp = remoteLastSync,
            premiumExpiresAt = remoteExpiresAt
        )

        saveProfile(profile)

        // Strictly update local preference with the single source of truth from Firestore
        try {
            FocusLockApp.instance.preferencesRepository.setProUser(remoteIsPro)
        } catch (e: Exception) {
            Log.w("AuthRepository", "Failed to update preferencesRepository: ${e.message}")
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

                // Requirement 3 & 6: write to user's own document in "users" collection:
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

    /**
     * Stats sync to Firestore. Only synchronizes streak and focus statistics.
     * Does NOT touch or overwrite isPro / premiumExpiresAt in Firestore.
     */
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

        val updatedProfile = UserProfile(
            uid = fbUser?.uid ?: current?.uid ?: "local_warrior",
            email = fbUser?.email ?: current?.email ?: "",
            displayName = fbUser?.displayName ?: current?.displayName ?: "Focus Warrior",
            photoUrl = fbUser?.photoUrl?.toString() ?: current?.photoUrl,
            streak = updatedStreak,
            totalFocusMinutes = updatedMinutes,
            sessionsCompleted = updatedSessions,
            isPro = current?.isPro ?: false,
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

    fun clearAccountDisabledMessage() {
        _accountDisabledMessage.value = null
    }

    private fun isUserDisabledException(e: Throwable): Boolean {
        var curr: Throwable? = e
        while (curr != null) {
            if (curr is FirebaseAuthInvalidUserException) {
                val code = curr.errorCode
                val msg = (curr.message ?: "").lowercase()
                if (code == "ERROR_USER_DISABLED" ||
                    msg.contains("disabled") ||
                    msg.contains("user-disabled") ||
                    msg.contains("user_disabled") ||
                    msg.contains("blocked")
                ) {
                    return true
                }
            }
            if (curr is FirebaseAuthException) {
                val code = curr.errorCode
                val msg = (curr.message ?: "").lowercase()
                if (code == "ERROR_USER_DISABLED" ||
                    msg.contains("user-disabled") ||
                    msg.contains("user_disabled") ||
                    msg.contains("disabled")
                ) {
                    return true
                }
            }
            val msg = (curr.message ?: "").lowercase()
            if (msg.contains("user-disabled") ||
                msg.contains("user_disabled") ||
                msg.contains("error_user_disabled") ||
                msg.contains("the user account has been disabled") ||
                msg.contains("user account has been disabled") ||
                msg.contains("account has been disabled")
            ) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    private fun isUserNotFoundOrDeletedException(e: Throwable): Boolean {
        var curr: Throwable? = e
        while (curr != null) {
            if (curr is FirebaseAuthInvalidUserException) {
                val code = curr.errorCode
                val msg = (curr.message ?: "").lowercase()
                if (code == "ERROR_USER_NOT_FOUND" ||
                    msg.contains("user-not-found") ||
                    msg.contains("not found") ||
                    msg.contains("deleted")
                ) {
                    return true
                }
            }
            val msg = (curr.message ?: "").lowercase()
            if (msg.contains("user_not_found") || msg.contains("user-not-found") || msg.contains("error_user_not_found")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    private fun mapAuthException(e: Exception): String {
        if (isUserDisabledException(e)) {
            return "Your account has been disabled."
        }
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
