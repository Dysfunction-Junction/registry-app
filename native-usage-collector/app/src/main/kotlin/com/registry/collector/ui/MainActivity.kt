package com.registry.collector.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.registry.collector.R
import com.registry.collector.data.SyncPreferences
import com.registry.collector.switchlog.SharedPrefsCursorStore
import com.registry.collector.switchlog.SwitchLogScheduler
import com.registry.collector.switchlog.SwitchLogStatusReader
import com.registry.collector.switchlog.switchLogDir
import com.registry.collector.worker.CollectorScheduler
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var syncPreferences: SyncPreferences

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Re-check permission status when returning from Settings
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    // -- Views --
    private lateinit var authSection: LinearLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var signInButton: Button
    private lateinit var signedInSection: LinearLayout
    private lateinit var signedInAs: TextView
    private lateinit var signOutButton: Button
    private lateinit var permissionStatus: TextView
    private lateinit var grantPermissionButton: Button
    private lateinit var syncSection: LinearLayout
    private lateinit var lastSyncText: TextView
    private lateinit var syncNowButton: Button
    private lateinit var syncScheduledText: TextView
    private lateinit var switchLogStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun buildUI() {
        val padding = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // This device draws edge-to-edge by default, so without this the root
        // layout's content (starting with the "Email" label) renders underneath
        // the status bar and is clipped/illegible. Pad the root by the actual
        // system bar insets instead of a fixed guess.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }

        // --- Auth Section (sign-in form) ---
        // TextInputEditText must be the child of a TextInputLayout — used bare
        // (as this was before), it has no Material theming to derive its text/
        // hint color from and can render illegibly (e.g. dark-on-dark) depending
        // on the device theme. TextInputLayout also gives us a proper floating
        // label instead of a hint that disappears on focus.
        authSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val emailLayout = TextInputLayout(this).apply { hint = "Email" }
        emailInput = TextInputEditText(emailLayout.context)
        emailLayout.addView(emailInput)

        val passwordLayout = TextInputLayout(this).apply {
            hint = "Password"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        passwordInput = TextInputEditText(passwordLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordLayout.addView(passwordInput)

        signInButton = Button(this).apply {
            text = getString(R.string.sign_in)
            setOnClickListener { signIn() }
        }
        authSection.addView(emailLayout)
        authSection.addView(passwordLayout)
        authSection.addView(signInButton)

        // --- Signed-in Section ---
        signedInSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        signedInAs = TextView(this)
        signOutButton = Button(this).apply {
            text = getString(R.string.sign_out)
            setOnClickListener { signOut() }
        }
        signedInSection.addView(signedInAs)
        signedInSection.addView(signOutButton)

        // --- Permission Section ---
        permissionStatus = TextView(this).apply {
            setPadding(0, padding, 0, 0)
        }
        grantPermissionButton = Button(this).apply {
            text = getString(R.string.grant_permission)
            setOnClickListener { openUsageAccessSettings() }
        }

        // --- Sync Section ---
        syncSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lastSyncText = TextView(this).apply {
            setPadding(0, padding, 0, 0)
        }
        syncNowButton = Button(this).apply {
            text = getString(R.string.sync_now)
            setOnClickListener { triggerImmediateSync() }
        }
        syncScheduledText = TextView(this).apply {
            text = getString(R.string.sync_scheduled)
        }
        syncSection.addView(lastSyncText)
        syncSection.addView(syncNowButton)
        syncSection.addView(syncScheduledText)

        root.addView(authSection)
        root.addView(signedInSection)
        root.addView(permissionStatus)
        root.addView(grantPermissionButton)
        root.addView(syncSection)

        // Switch-log status sits outside syncSection, which is hidden when signed out.
        switchLogStatus = TextView(this).apply { setPadding(0, padding, 0, 0) }
        root.addView(switchLogStatus)

        setContentView(root)
    }

    private fun updateUI() {
        val user = auth.currentUser
        val hasPermission = hasUsageStatsPermission()

        // Auth visibility
        authSection.visibility = if (user == null) View.VISIBLE else View.GONE
        signedInSection.visibility = if (user != null) View.VISIBLE else View.GONE
        if (user != null) {
            signedInAs.text = getString(R.string.signed_in_as, user.email ?: "unknown")
        }

        // Permission visibility
        permissionStatus.text = if (hasPermission) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_denied)
        }
        grantPermissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE

        updateSwitchLogStatus(hasPermission)

        // Sync section — only show and schedule when signed in AND permission granted
        val isReady = CollectorScheduler.isSyncReady(this)
        syncSection.visibility = if (isReady) View.VISIBLE else View.GONE
        if (isReady) {
            val lastSync = syncPreferences.lastSuccessfulSync
            lastSyncText.text = if (lastSync > 0) {
                val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(lastSync))
                getString(R.string.last_sync, formatted)
            } else {
                getString(R.string.last_sync_never)
            }

            // Schedule periodic worker (idempotent — KEEP policy)
            CollectorScheduler.schedule(this)
        } else {
            // Cancel periodic worker if auth or permission is revoked
            CollectorScheduler.cancel(this)
        }
    }

    private fun updateSwitchLogStatus(hasPermission: Boolean) {
        if (hasPermission) SwitchLogScheduler.enqueueBackfillIfNeeded(this)
        val store = SharedPrefsCursorStore(this)
        val st = SwitchLogStatusReader.read(switchLogDir(this))
        val none = getString(R.string.switch_log_none)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fun at(ms: Long?) = if (ms == null || ms <= 0L) none else fmt.format(Date(ms))
        switchLogStatus.text = getString(
            R.string.switch_log_status,
            st.fileCount, st.lineCount, at(st.firstTs), at(st.lastTs),
            at(store.lastRunMs()), store.lastRunEvents(), at(store.getBackfillEarliest()),
        )
    }

    private fun signIn() {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { updateUI() }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun signOut() {
        auth.signOut()
        updateUI()
    }

    private fun openUsageAccessSettings() {
        settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun triggerImmediateSync() {
        CollectorScheduler.triggerImmediate(this)
        Toast.makeText(this, "Sync triggered", Toast.LENGTH_SHORT).show()
    }

    private fun hasUsageStatsPermission(): Boolean {
        return CollectorScheduler.hasUsageStatsPermission(this)
    }
}
