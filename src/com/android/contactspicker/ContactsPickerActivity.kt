/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.contactspicker

import android.app.ApplicationPackageManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.EXTRA_EXCLUDE_COMPONENTS
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Trace
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.contactspicker.data.model.shouldDisableRecentsScreenshot
import com.android.contactspicker.provider.CallingPackageProvider
import com.android.contactspicker.ui.components.ContactsPickerBottomSheet
import com.android.contactspicker.ui.theme.ContactsPickerAppTheme
import com.android.contactspicker.viewmodel.ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD
import com.android.contactspicker.viewmodel.ContactsViewModel
import com.android.contactspicker.viewmodel.PickerResultEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The main activity for the Contacts Picker system app. */
@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint(ComponentActivity::class)
class ContactsPickerActivity : Hilt_ContactsPickerActivity() {

    companion object {
        private const val TAG = "ContactsPickerActivity"
    }

    @Inject lateinit var appPackageManager: ApplicationPackageManager

    @Inject lateinit var callingPackageProvider: CallingPackageProvider

    private val contactsViewModel: ContactsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.beginSection("$TAG#coldStart")
        super.onCreate(savedInstanceState)

        window.setHideOverlayWindows(true)

        if (savedInstanceState != null) {
            setupComposeUi()
            return
        }

        routeIntent(intent)
        Trace.endSection()
    }

    override fun onNewIntent(intent: Intent) {
        Trace.beginSection("$TAG#onNewIntent")
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
        Trace.endSection()
    }

    /**
     * Determines the correct handling for the intent based on the presence of the
     * [Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER] extra and the calling package target SDK.
     */
    private fun routeIntent(intent: Intent) {
        val callingPackage = callingPackageProvider.get()
        if (callingPackage == null) {
            Log.e(TAG, "Cannot get calling package. Finishing with RESULT_CANCELED.")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val (callingAppName, callingAppUid, callingAppTargetSdk) =
            try {
                val appInfo = appPackageManager.getApplicationInfo(callingPackage, 0)
                Triple(
                    appPackageManager.getApplicationLabel(appInfo).toString(),
                    callingPackageProvider.getCallingAppUid(),
                    appInfo.targetSdkVersion,
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Calling package not found: $callingPackage", e)
                Triple(null, -1, ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD)
            }

        try {
            val handleInternally =
                contactsViewModel.handleIntent(
                    intentAction = intent.action,
                    intentType = intent.resolveType(this),
                    intentExtras = intent.extras,
                    callingAppName = callingAppName,
                    callingPackageName = callingPackage,
                    callingAppUid = callingAppUid,
                    callingAppTargetSdk = callingAppTargetSdk,
                )

            if (handleInternally) {
                setupComposeUi()
            } else {
                executeForwardingLogic()
            }
        } catch (e: IllegalArgumentException) {
            // TODO(b/473814215) Display a toast with error message before finishing the activity.
            Log.e(TAG, "Error processing intent", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun executeForwardingLogic() {
        val forwardIntent =
            Intent(intent).apply {
                component = null
                setPackage(null)
            }
        if (!forwardToPreferredActionPickHandler(forwardIntent)) {
            forwardToOtherActionPickHandlersWithChooser(forwardIntent)
        }
    }

    /**
     * Forwards the intent to a preselected preferred handler, if present.
     *
     * @return `true` if the intent was forwarded, `false` otherwise.
     */
    private fun forwardToPreferredActionPickHandler(targetIntent: Intent): Boolean {
        val filters = mutableListOf<IntentFilter>()
        val activities = mutableListOf<ComponentName>()
        appPackageManager.getPreferredActivities(filters, activities, null)

        val preferredActivity =
            filters.zip(activities).find { (filter, _) ->
                // Only match preferred activities within the current user's context, as reading
                // cross-profile contacts data will anyways fail, even if the user selects a
                // cross-profile app, due to CP2 limitation.
                filter.match(contentResolver, targetIntent, false, TAG) > 0
            }

        if (preferredActivity != null) {
            val (_, component) = preferredActivity
            Log.d(TAG, "Starting Preferred activity. Component: $component")
            val preferredActivityIntent =
                Intent(intent).apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                }
            startActivity(preferredActivityIntent)
            finish()
            return true
        }

        Log.d(TAG, "No PreferredActivity Found")
        return false
    }

    // Sets up the Compose UI. Should be used only when the contacts are already loaded, e.g. on
    // configuration change.
    private fun setupComposeUi() {
        setContent {
            LaunchedEffect(Unit) {
                contactsViewModel.pickerResultEvents.collect { event ->
                    when (event) {
                        is PickerResultEvent.SetResultAndFinish -> {
                            setResult(RESULT_OK, event.intent)
                            finish()
                        }
                        is PickerResultEvent.CancelAndFinish -> {
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                    }
                }
            }

            val uiState = contactsViewModel.uiState.collectAsStateWithLifecycle()
            val userState by contactsViewModel.userState.collectAsStateWithLifecycle()

            LaunchedEffect(userState) {
                setRecentsScreenshotEnabled(!userState.shouldDisableRecentsScreenshot())
            }

            ContactsPickerAppTheme {
                ContactsPickerBottomSheet(
                    onDismissRequest = { finish() },
                    uiState = uiState,
                    userState = userState,
                    snackbarEvents = contactsViewModel.snackbarEvents,
                    onToggleContactSelection = contactsViewModel::toggleContactSelection,
                    onToggleEntrySelection = contactsViewModel::toggleEntrySelection,
                    onClearSelection = contactsViewModel::clearSelection,
                    onPrivacyDetailsBannerClicked =
                        contactsViewModel::onPrivacyDetailsBannerClicked,
                    onPrivacyDetailsOverflowMenuClicked =
                        contactsViewModel::onPrivacyDetailsOverflowMenuClicked,
                    onBackFromPrivacyDetails = contactsViewModel::onBackFromPrivacyDetails,
                    onPrivacyBannerDismissRequest = contactsViewModel::hidePrivacyBanner,
                    onDoneClicked = contactsViewModel::onDoneClicked,
                    onQueryChange = contactsViewModel::onSearchQueryChanged,
                    onExitSearch = contactsViewModel::exitSearch,
                    onPreviewClicked = contactsViewModel::onPreviewClicked,
                    onBackFromPreview = contactsViewModel::onBackFromPreview,
                    onProfileClicked = contactsViewModel::onProfileClicked,
                    onDismissProfileBlockedDialog = contactsViewModel::dismissProfileBlockedDialog,
                )
            }
        }
    }

    private fun forwardToOtherActionPickHandlersWithChooser(targetIntent: Intent) {
        val excludedComponents = arrayOf(ComponentName(this, ContactsPickerActivity::class.java))
        val chooserIntent =
            Intent.createChooser(
                    targetIntent,
                    getString(R.string.contacts_picker_chooser_activity_title),
                )
                .apply {
                    putExtra(EXTRA_EXCLUDE_COMPONENTS, excludedComponents)
                    addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)

                    if (targetIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (targetIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                }

        try {
            startActivity(chooserIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No Activity found to handle the intent: $targetIntent", e)
            Toast.makeText(
                    this,
                    getString(R.string.contacts_picker_chooser_activity_no_app_can_handle_action),
                    Toast.LENGTH_SHORT,
                )
                .show()
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
