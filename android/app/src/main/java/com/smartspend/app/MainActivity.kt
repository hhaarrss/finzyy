package com.smartspend.app

import android.content.Context
import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.smartspend.app.ui.auth.LinkPhoneScreen
import com.smartspend.app.ui.auth.LoadingPage
import com.smartspend.app.ui.auth.PhoneAuthController
import com.smartspend.app.ui.auth.ProfileSetupScreen
import com.smartspend.app.ui.auth.SignInScreen
import com.smartspend.app.ui.auth.serverMessage
import com.smartspend.app.ui.navigation.SmartSpendNavHost
import com.smartspend.app.ui.onboarding.OnboardingCarousel
import com.smartspend.app.ui.onboarding.SplashScreen
import com.smartspend.app.ui.permission.smsPermissionsGranted
import com.smartspend.app.ui.theme.SmartSpendTheme
import com.smartspend.app.ui.theme.ThemePreference
import com.smartspend.app.ui.theme.isDark
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Entry point. Signed out, it shows the Compose sign-in flow (mobile number + OTP, Google, or
 * email for older accounts). After sign-in it asks accounts without a phone to add one, sends
 * new or incomplete profiles to profile setup, then hands the window to the Compose app in `ui/`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var googleSignInClient: GoogleSignInClient

    private var mainAppShown = false
    private var googleBusy by mutableStateOf(false)
    private var googleError by mutableStateOf<String?>(null)

    /** SmsReceiver / the interceptor drop the token when the backend rejects it; follow it back to sign-in. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == AuthSession.KEY_TOKEN && mainAppShown && prefs.getString(AuthSession.KEY_TOKEN, null).isNullOrEmpty()) {
            showSignIn()
        }
    }

    /** Result is read where it matters (notificationsAllowed); nothing to do here. */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** Google account picker result -> Firebase credential -> Finzyy session. */
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrEmpty()) {
                googleBusy = false
                googleError = "Google sign-in isn't configured for this build. Use your mobile number instead."
                return@registerForActivityResult
            }
            signInWithGoogle(idToken)
        } catch (e: ApiException) {
            googleBusy = false
            googleError = when (e.statusCode) {
                12501 -> null // cancelled by the user
                10 -> "Google sign-in isn't set up for this app build (SHA fingerprint missing in Firebase)."
                7 -> "No internet connection. Check your network and try again."
                else -> "Google sign-in failed (code ${e.statusCode}). Try again or use your mobile number."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        ThemePreference.load(this)

        // Keeps bank SMS syncing (and retrying) in the background while the app is closed.
        SmsSyncWorker.schedulePeriodic(this)

        if (BuildConfig.DEV_SKIP_AUTH) {
            // The dev-stub backend (AUTH_STUB=true) ignores the token's contents and always
            // resolves to the seeded stub user, but every client call — including SmsReceiver's
            // background ingest — still checks for a non-empty token locally before it will even
            // attempt the request. Without this, real incoming SMS are parsed but never synced.
            val devPrefs = getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)
            if (devPrefs.getString("jwt_token", null).isNullOrEmpty()) {
                devPrefs.edit()
                    .putString("jwt_token", "dev-stub-token")
                    .putString("user_email", "dev@smartspend.app")
                    .apply()
            }
        }

        val webClientId = getString(R.string.default_web_client_id)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (webClientId.isNotBlank() && !webClientId.contains("placeholder")) {
            gsoBuilder.requestIdToken(webClientId)
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        sharedPrefs = getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionStore.unauthorized.collect { handleUnauthorized() }
            }
        }

        // Splash only on a real cold start — not on rotation or process-restore.
        if (savedInstanceState == null) showSplash() else navigateToCorrectScreen()
    }

    override fun onDestroy() {
        if (::sharedPrefs.isInitialized) sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    private var hasResumedOnce = false

    override fun onResume() {
        super.onResume()
        val token = sharedPrefs.getString("jwt_token", null)
        if (!token.isNullOrEmpty()) {
            // Push anything queued while offline / logged out and catch up on missed SMS.
            SmsSyncWorker.enqueueNow(this)
            // Transactions may have been synced while the app was in the background: refresh open screens.
            if (hasResumedOnce) TransactionEvents.notifyChanged()
            registerFcmTokenIfAvailable(token)
        }
        hasResumedOnce = true
    }

    private fun registerFcmTokenIfAvailable(jwtToken: String) {
        val fcmToken = sharedPrefs.getString("fcm_token", null)
        if (!fcmToken.isNullOrEmpty()) {
            lifecycleScope.launch {
                try {
                    RetrofitClient.apiService.registerFcmToken("Bearer $jwtToken", FcmTokenPayload(fcmToken))
                } catch (e: Exception) {
                    // Suppress
                }
            }
        }
    }

    private fun navigateToCorrectScreen() {
        if (AuthSession.isSignedIn(this)) continueAfterSignIn() else showSignIn()
    }

    /**
     * After any sign-in (and on every signed-in start): accounts without a phone are asked to
     * add one (once, skippable), incomplete profiles go to profile setup, then the app.
     */
    private fun continueAfterSignIn() {
        googleBusy = false
        googleError = null
        if (BuildConfig.DEV_SKIP_AUTH) {
            showDashboard()
            return
        }
        when {
            // Signed in before profiles existed: ask the backend once, then decide.
            AuthSession.profileComplete(this) == null -> refreshProfileThenContinue()
            AuthSession.shouldPromptPhoneLink(this) -> showLinkPhone()
            AuthSession.profileComplete(this) == false -> showProfileSetup()
            else -> showDashboard()
        }
    }

    private fun refreshProfileThenContinue() {
        showLoading()
        lifecycleScope.launch {
            val profile = withTimeoutOrNull(10_000) {
                runCatching { RetrofitClient.apiService.getMyProfile() }.getOrNull()?.takeIf { it.isSuccessful }?.body()
            }
            if (profile != null) {
                AuthSession.update(this@MainActivity, profile)
                continueAfterSignIn()
            } else if (AuthSession.isSignedIn(this@MainActivity)) {
                // Offline or server asleep: don't block the app on it; ask again next start.
                showDashboard()
            }
        }
    }

    /**
     * Every cold start: splash (2.4s) → onboarding carousel whenever nobody is signed in → login.
     * A signed-in user goes from the splash straight to the app.
     */
    private fun showSplash() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            SmartSpendTheme(darkTheme = ThemePreference.mode.isDark()) {
                SplashScreen(onFinished = ::afterSplash)
            }
        }
    }

    private fun afterSplash() {
        val signedIn = !sharedPrefs.getString("jwt_token", null).isNullOrEmpty()
        if (signedIn) {
            navigateToCorrectScreen()
        } else {
            showOnboarding()
        }
    }

    private fun showOnboarding(startPage: Int = 0) {
        mainAppShown = false
        setContent {
            SmartSpendTheme(darkTheme = ThemePreference.mode.isDark()) {
                OnboardingCarousel(
                    startPage = startPage,
                    onSignUp = ::showSignIn,
                    onLogIn = ::showSignIn
                )
            }
        }
    }

    /** Pages outside the signed-in app, with bar icons that follow the app theme. */
    private fun setAuthContent(content: @Composable () -> Unit) {
        mainAppShown = false
        setContent {
            val dark = ThemePreference.mode.isDark()
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
            }
            SmartSpendTheme(darkTheme = dark) { content() }
        }
    }

    private fun showSignIn() {
        googleBusy = false
        setAuthContent {
            BackHandler { showOnboarding(startPage = 2) }
            SignInScreen(
                onSignedIn = { continueAfterSignIn() },
                onGoogle = ::launchGoogleSignIn,
                googleBusy = googleBusy,
                googleError = googleError,
                onBack = { showOnboarding(startPage = 2) }
            )
        }
    }

    private fun showLinkPhone() {
        setAuthContent {
            LinkPhoneScreen(
                onLinked = { continueAfterSignIn() },
                onSkip = {
                    AuthSession.markPhonePrompted(this)
                    continueAfterSignIn()
                },
                onBack = null
            )
        }
    }

    private fun showProfileSetup() {
        setAuthContent {
            val profile by produceState<UserData?>(null) {
                value = runCatching { RetrofitClient.apiService.getMyProfile() }.getOrNull()?.body()
                    ?: UserData(
                        id = 0,
                        email = sharedPrefs.getString("user_email", null),
                        phone_number = sharedPrefs.getString("user_phone", null),
                        full_name = sharedPrefs.getString("user_name", null)
                    )
            }
            val initial = profile
            if (initial == null) {
                LoadingPage()
            } else {
                ProfileSetupScreen(
                    initial = initial,
                    editing = false,
                    onSaved = { continueAfterSignIn() },
                    onBack = null
                )
            }
        }
    }

    private fun showLoading() {
        setAuthContent { LoadingPage() }
    }

    /** Hands the window to the signed-in Compose app. */
    private fun showDashboard() {
        if (mainAppShown) return
        mainAppShown = true

        // First sign-in on this device: show the SMS disclosure once, instead of the old
        // unexplained permission prompt at launch.
        val promptConsent = !smsPermissionsGranted(this) &&
            !sharedPrefs.getBoolean(PREF_CONSENT_PROMPTED, false)
        if (promptConsent) sharedPrefs.edit().putBoolean(PREF_CONSENT_PROMPTED, true).apply()

        // Android 13+ drops every notification (sync alerts, budget pushes) until the app holds
        // POST_NOTIFICATIONS. Not stacked on the SMS consent screen — that launch asks for SMS;
        // the next one asks for this. The system itself stops showing it after two denials.
        if (!promptConsent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val dark = ThemePreference.mode.isDark()
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
            }
            SmartSpendTheme(darkTheme = dark) {
                SmartSpendNavHost(
                    onSignedOut = { performLogout() },
                    promptSmsConsent = promptConsent
                )
            }
        }
    }

    private fun handleUnauthorized() {
        if (!mainAppShown) return
        AuthSession.clear(this)
        Toast.makeText(this, "Session expired. Please sign in again.", Toast.LENGTH_LONG).show()
        showSignIn()
    }

    private fun performLogout() {
        PhoneAuthController.signOut()
        runCatching { googleSignInClient.signOut() }
        AuthSession.clear(this)
        showSignIn()
    }

    private fun launchGoogleSignIn() {
        googleError = null
        googleBusy = true
        // Always show the account picker, even if a Google account was used before.
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun signInWithGoogle(googleIdToken: String) {
        googleBusy = true
        lifecycleScope.launch {
            val firebaseToken = PhoneAuthController.signIn(GoogleAuthProvider.getCredential(googleIdToken, null))
                .getOrElse {
                    googleBusy = false
                    googleError = it.message
                    return@launch
                }
            val response = runCatching { RetrofitClient.apiService.firebaseLogin(FirebaseTokenPayload(firebaseToken)) }.getOrNull()
            val body = response?.body()
            when {
                response == null -> googleError = "Can't reach Finzyy. Check your internet and try again."
                response.isSuccessful && body != null -> {
                    AuthSession.save(this@MainActivity, body)
                    continueAfterSignIn()
                }
                else -> googleError = serverMessage(response, "Google sign-in failed (${response.code()}).")
            }
            googleBusy = false
        }
    }

    companion object {
        private const val PREF_CONSENT_PROMPTED = "sms_consent_prompted"
    }
}
