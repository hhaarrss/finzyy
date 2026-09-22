package com.smartspend.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.smartspend.app.databinding.ActivityMainBinding
import com.smartspend.app.ui.navigation.SmartSpendNavHost
import com.smartspend.app.ui.onboarding.OnboardingCarousel
import com.smartspend.app.ui.onboarding.SplashScreen
import com.smartspend.app.ui.permission.smsPermissionsGranted
import com.smartspend.app.ui.theme.SmartSpendTheme
import com.smartspend.app.ui.theme.ThemePreference
import com.smartspend.app.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point. Signed out, it shows the XML login/register screen (owned separately — those
 * functions are carried over unchanged below). Signed in, it hands the window to the Compose
 * app in `ui/`, which replaced the old XML dashboard (Home/Add/Budget/Insights/Profile tabs).
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private var isRegisterMode = false
    private var mainAppShown = false

    /** SmsReceiver drops the token when the backend rejects it; follow it back to login. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "jwt_token" && mainAppShown && prefs.getString("jwt_token", null).isNullOrEmpty()) {
            openAuth(signUp = false)
        }
    }

    /** Handles result from Google Sign-In intent. */
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (!idToken.isNullOrEmpty()) {
                firebaseAuthWithGoogle(idToken)
            } else {
                val email = account.email ?: "google_user@smartspend.app"
                val displayName = account.displayName ?: email.substringBefore("@")
                sharedPrefs.edit()
                    .putString("jwt_token", "google_${account.id ?: email.hashCode()}")
                    .putString("user_email", email)
                    .apply()
                Toast.makeText(this, "Welcome, $displayName! 👋", Toast.LENGTH_SHORT).show()
                showDashboard()
            }
        } catch (e: ApiException) {
            val errorMsg = when (e.statusCode) {
                10 -> "Google Sign-In configuration error (Code 10: SHA-1 fingerprint needs to be added in Firebase Console)."
                12500 -> "Sign in failed (Code 12500: Google Play Services issue or unlinked OAuth client)."
                12501 -> "Sign-in was cancelled."
                else -> "Google Sign-In failed (Code ${e.statusCode}): ${e.localizedMessage ?: "Unknown error"}"
            }
            if (e.statusCode == 10) {
                AlertDialog.Builder(this)
                    .setTitle("Google Sign-In Setup Required")
                    .setMessage("Error 10 indicates that your Android app's debug SHA-1 fingerprint has not been registered in the Firebase Console yet.\n\nPlease add your SHA-1 to Firebase Project Settings -> Android App.")
                    .setPositiveButton("OK", null)
                    .show()
            } else if (e.statusCode != 12501) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        ThemePreference.load(this)

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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyLoginInsets()

        auth = FirebaseAuth.getInstance()
        val webClientId = getString(R.string.default_web_client_id)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (webClientId.isNotBlank() && !webClientId.contains("placeholder")) {
            gsoBuilder.requestIdToken(webClientId)
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        sharedPrefs = getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        setupListeners()
        onBackPressedDispatcher.addCallback(this, authBack)
        binding.loginBack.setOnClickListener { showOnboarding(startPage = 2) }
        // The toggle link flips modes in place; keep the mode-specific label in step with it.
        binding.llFullName.viewTreeObserver.addOnGlobalLayoutListener { syncAuthModeUi() }

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

    override fun onResume() {
        super.onResume()
        val token = sharedPrefs.getString("jwt_token", null)
        if (!token.isNullOrEmpty()) {
            registerFcmTokenIfAvailable(token)
        }
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
        val token = sharedPrefs.getString("jwt_token", null)
        if (token.isNullOrEmpty()) {
            openAuth(signUp = false)
        } else {
            showDashboard()
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
        authBack.isEnabled = false
        setContent {
            SmartSpendTheme(darkTheme = ThemePreference.mode.isDark()) {
                OnboardingCarousel(
                    startPage = startPage,
                    onSignUp = { openAuth(signUp = true) },
                    onLogIn = { openAuth(signUp = false) }
                )
            }
        }
    }

    /**
     * Log in and Sign up are the login screen's two existing modes. Switching goes through the
     * screen's own toggle link, so the login code is exercised exactly as if the user tapped it.
     */
    private fun openAuth(signUp: Boolean) {
        showLogin()
        if (isRegisterMode != signUp) binding.btnToggleAuthMode.performClick()
        syncAuthModeUi()
        binding.loginSection.scrollTo(0, 0)
    }

    /** Label/hint that differ between the two pages; kept in step with the toggle's mode. */
    private fun syncAuthModeUi() {
        val label = if (isRegisterMode) "EMAIL" else "EMAIL OR PHONE"
        val hint = if (isRegisterMode) "you@example.com" else "Email or phone number"
        // Guarded: setText requests a layout, and this runs from a layout listener.
        if (binding.tvEmailLabel.text.toString() != label) binding.tvEmailLabel.text = label
        if (binding.etEmail.hint?.toString() != hint) binding.etEmail.hint = hint
    }

    /** Back on the log in / sign up page returns to the carousel's last page. */
    private val authBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showOnboarding(startPage = 2)
    }

    private fun showLogin() {
        if (binding.root.parent == null) {
            // Leaving Compose (splash, onboarding or the signed-in app): put the XML back.
            mainAppShown = false
            setContentView(binding.root)
        }
        // Edge-to-edge like the rest of the app: light icons over the violet hero. The layout
        // pads itself for the bars and keyboard (see applyLoginInsets).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        binding.loginSection.visibility = View.VISIBLE
        authBack.isEnabled = true
    }

    /** Hero extends under the status bar; the scroll content clears the nav bar and keyboard. */
    private fun applyLoginInsets() {
        val heroTop = binding.loginHero.paddingTop
        val contentBottom = binding.loginContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.loginHero.updatePadding(top = heroTop + bars.top)
            binding.loginContent.updatePadding(bottom = contentBottom + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    /** Called by the login flow on success. Hands the window to the Compose app. */
    private fun showDashboard() {
        if (mainAppShown) return
        mainAppShown = true
        authBack.isEnabled = false
        binding.loginSection.visibility = View.GONE

        // First sign-in on this device: show the SMS disclosure once, instead of the old
        // unexplained permission prompt at launch.
        val promptConsent = !smsPermissionsGranted(this) &&
            !sharedPrefs.getBoolean(PREF_CONSENT_PROMPTED, false)
        if (promptConsent) sharedPrefs.edit().putBoolean(PREF_CONSENT_PROMPTED, true).apply()

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
        sharedPrefs.edit().remove("jwt_token").apply()
        Toast.makeText(this, "Session expired. Please sign in again.", Toast.LENGTH_LONG).show()
        openAuth(signUp = false)
    }

    private fun performLogout() {
        try {
            auth.signOut()
            googleSignInClient.signOut()
        } catch (e: Exception) {
            // Local session cleanup still proceeds if provider sign-out fails.
        }
        sharedPrefs.edit()
            .remove("jwt_token")
            .remove("user_email")
            .apply()
        openAuth(signUp = false)
    }

    // ──────────────── Login / Register (carried over unchanged) ────────────────

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener { performLogin() }
        binding.btnGoogleSignIn.setOnClickListener { launchGoogleSignIn() }
        binding.btnToggleAuthMode.setOnClickListener {
            isRegisterMode = !isRegisterMode
            if (isRegisterMode) {
                binding.tvLoginTitle.text = "Create Account"
                binding.tvLoginSubtitle.text = "Join SmartSpend to track your expenses"
                binding.llFullName.visibility = View.VISIBLE
                binding.btnLogin.text = "Sign Up"
                binding.btnToggleAuthMode.text = "Already have an account? Sign In"
            } else {
                binding.tvLoginTitle.text = getString(R.string.login_title)
                binding.tvLoginSubtitle.text = "Sign in to pair your Android device with your Expense Dashboard"
                binding.llFullName.visibility = View.GONE
                binding.btnLogin.text = getString(R.string.btn_login)
                binding.btnToggleAuthMode.text = "Don't have an account? Sign up"
            }
        }
    }

    private fun launchGoogleSignIn() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        binding.btnGoogleSignIn.isClickable = false
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                binding.btnGoogleSignIn.isClickable = true
                if (task.isSuccessful) {
                    val fbUser = auth.currentUser
                    val email = fbUser?.email ?: "google_user@smartspend.app"
                    val displayName = fbUser?.displayName ?: email.substringBefore("@")
                    sharedPrefs.edit()
                        .putString("jwt_token", "google_${fbUser?.uid}")
                        .putString("user_email", email)
                        .apply()
                    Toast.makeText(this, "Welcome, $displayName! 👋", Toast.LENGTH_SHORT).show()
                    showDashboard()
                } else {
                    Toast.makeText(this, "Google Sign-In failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val fullName = binding.etFullName.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isRegisterMode && fullName.isEmpty()) {
            Toast.makeText(this, "Please enter your full name", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = if (isRegisterMode) "Creating Account..." else "Signing in..."

        lifecycleScope.launch {
            try {
                // Authenticate with backend API for JWT
                val resp = if (isRegisterMode) {
                    RetrofitClient.apiService.register(RegisterPayload(email, fullName, password))
                } else {
                    RetrofitClient.apiService.login(email, password)
                }
                
                if (resp.isSuccessful && resp.body() != null) {
                    val token = resp.body()!!.access_token
                    sharedPrefs.edit()
                        .putString("jwt_token", token)
                        .putString("user_email", email)
                        .apply()

                    // 2. Synchronize with Firebase Auth
                    try {
                        auth.signInWithEmailAndPassword(email, password)
                            .addOnCompleteListener { fbTask ->
                                if (!fbTask.isSuccessful) {
                                    auth.createUserWithEmailAndPassword(email, password)
                                }
                            }
                    } catch (fbEx: Exception) {
                        // Suppress Firebase sync errors
                    }

                    withContext(Dispatchers.Main) {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = if (isRegisterMode) "Sign Up" else "Sign In"
                        val welcomeMsg = if (isRegisterMode) "Account created! Welcome, $fullName! 👋" else "Welcome back, $email! 👋"
                        Toast.makeText(this@MainActivity, welcomeMsg, Toast.LENGTH_SHORT).show()
                        showDashboard()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = if (isRegisterMode) "Sign Up" else "Sign In"
                        Toast.makeText(this@MainActivity, "Login failed: ${resp.code()} (Invalid credentials)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = if (isRegisterMode) "Sign Up" else "Sign In"
                    Toast.makeText(this@MainActivity, "Connection error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val PREF_CONSENT_PROMPTED = "sms_consent_prompted"
    }
}
