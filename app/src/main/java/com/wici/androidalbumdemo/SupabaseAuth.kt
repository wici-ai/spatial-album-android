package com.wici.androidalbumdemo

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Google login against Supabase, kept deliberately lightweight: native Credential Manager to get a
 * Google ID token, then a plain REST call to Supabase's GoTrue endpoint (no supabase-kt SDK, no
 * ktor, no coroutines — matching the rest of this app's HttpURLConnection style).
 *
 * Only used when MainActivity's cloud-backend gate is true (i.e. the hosted app.wici.ai backend).
 * The Supabase session is stored so the user stays signed in across launches. The access token is
 * not yet sent to the backend — that is a later phase once the backend verifies the JWT. Email +
 * password + verification codes are also a later phase; [signInWithGoogle] is the only path here.
 */
object SupabaseAuth {

    // Public, embeddable config (anon key is meant to ship in clients).
    const val SUPABASE_URL = "https://nqwatleukvclebomymdb.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5xd2F0bGV1a3ZjbGVib215bWRiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODI4NzIyNDUsImV4cCI6MjA5ODQ0ODI0NX0.lVY55-oxfV-zO9XA6IszZjy0kd8Dw9LgDt7YKNoXuSM"
    // The Web OAuth client ID (serverClientId) — the audience Supabase validates against.
    const val GOOGLE_WEB_CLIENT_ID =
        "1097403621666-5eere3g8irq835mo9m2srjt32gkupc04.apps.googleusercontent.com"

    private const val PREFS_NAME = "android-album-demo"
    private const val K_ACCESS = "sb_access_token"
    private const val K_REFRESH = "sb_refresh_token"
    private const val K_EXPIRES = "sb_expires_at"
    private const val K_EMAIL = "sb_email"

    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Call once early in Activity.onCreate before checking [isLoggedIn]. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isLoggedIn(): Boolean = prefs().getString(K_REFRESH, null) != null

    fun email(): String? = prefs().getString(K_EMAIL, null)

    fun signOut() {
        prefs().edit()
            .remove(K_ACCESS).remove(K_REFRESH).remove(K_EXPIRES).remove(K_EMAIL)
            .apply()
    }

    /**
     * Launch the native "Sign in with Google" flow, then exchange the Google ID token for a
     * Supabase session. [onResult] is always delivered on the main thread.
     */
    fun signInWithGoogle(activity: Activity, onResult: (success: Boolean, message: String?) -> Unit) {
        val option = GetSignInWithGoogleOption.Builder(GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credentialManager = CredentialManager.create(activity)
        credentialManager.getCredentialAsync(
            activity,
            request,
            null,
            io,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential &&
                        cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        try {
                            val google = GoogleIdTokenCredential.createFrom(cred.data)
                            exchangeIdToken(google.idToken, onResult)
                        } catch (e: Exception) {
                            postMain { onResult(false, "Failed to parse Google credential: ${e.message}") }
                        }
                    } else {
                        postMain { onResult(false, "No Google credential returned") }
                    }
                }

                override fun onError(e: GetCredentialException) {
                    postMain { onResult(false, "Google sign-in canceled or failed: ${e.message}") }
                }
            }
        )
    }

    /** POST the Google ID token to Supabase GoTrue and persist the returned session. */
    private fun exchangeIdToken(idToken: String, onResult: (Boolean, String?) -> Unit) {
        io.execute {
            try {
                val conn = (URL("$SUPABASE_URL/auth/v1/token?grant_type=id_token")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                }
                val payload = JSONObject()
                    .put("provider", "google")
                    .put("id_token", idToken)
                    .toString()
                conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code in 200..299) {
                    saveSession(JSONObject(text))
                    postMain { onResult(true, null) }
                } else {
                    val msg = runCatching {
                        val j = JSONObject(text)
                        j.optString("error_description")
                            .ifEmpty { j.optString("msg") }
                            .ifEmpty { j.optString("error") }
                            .ifEmpty { text }
                    }.getOrDefault(text)
                    postMain { onResult(false, "Sign-in failed ($code): $msg") }
                }
            } catch (e: Exception) {
                postMain { onResult(false, "Network error: ${e.message}") }
            }
        }
    }

    private fun saveSession(json: JSONObject) {
        val nowSec = System.currentTimeMillis() / 1000
        val expiresAt = when {
            json.has("expires_at") -> json.optLong("expires_at", nowSec + 3600)
            else -> nowSec + json.optLong("expires_in", 3600)
        }
        val email = json.optJSONObject("user")?.optString("email").orEmpty()
        prefs().edit()
            .putString(K_ACCESS, json.optString("access_token"))
            .putString(K_REFRESH, json.optString("refresh_token"))
            .putLong(K_EXPIRES, expiresAt)
            .putString(K_EMAIL, email)
            .apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun postMain(block: () -> Unit) = main.post(block)
}
