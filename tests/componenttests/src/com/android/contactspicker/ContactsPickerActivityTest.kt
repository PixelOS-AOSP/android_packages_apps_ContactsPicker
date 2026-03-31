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

import android.app.Activity
import android.app.ApplicationPackageManager
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.flags.Flags
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.ContactsContract
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.contactspicker.Flags.FLAG_ENABLE_ACTION_PICK_TAKEOVER_IN_DROIDFOOD
import com.android.contactspicker.data.model.PickerUserState
import com.android.contactspicker.data.model.contactsSelectionOf
import com.android.contactspicker.data.model.emptyContactsSelection
import com.android.contactspicker.inject.ActivityModule
import com.android.contactspicker.inject.AppModule
import com.android.contactspicker.provider.CallingPackageProvider
import com.android.contactspicker.room.dao.PrivacyBannerShownDao
import com.android.contactspicker.testdata.ContactTestDataFactory
import com.android.contactspicker.ui.components.BOTTOM_SHEET_TEST_TAG
import com.android.contactspicker.viewmodel.ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD
import com.android.contactspicker.viewmodel.ContactsViewModel
import com.android.contactspicker.viewmodel.PickerResultEvent
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.util.TreeMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@UninstallModules(AppModule::class, ActivityModule::class)
@HiltAndroidTest
class ContactsPickerActivityTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 2) val composeTestRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var baseIntent: Intent

    @BindValue @JvmField val mockPackageManager: ApplicationPackageManager = mock()

    @BindValue @JvmField val mockCallingPackageProvider: CallingPackageProvider = mock()

    @BindValue val mockViewModel: ContactsViewModel = mock()
    @BindValue val mockPrivacyBannerShownDao: PrivacyBannerShownDao = mock()
    private val mockEventsFlow = MutableSharedFlow<PickerResultEvent>(replay = 1)

    private lateinit var testPackageName: String

    private val testUri = Uri.parse("content://contacts/1")
    private val testUri2 = Uri.parse("content://data/10")
    private val testContact = ContactTestDataFactory.GENERIC_DISPLAY_NAME_CONTACT

    @Before
    fun setUp() {
        Intents.init()
        hiltRule.inject()

        testPackageName = context.packageName
        val appInfo =
            ApplicationInfo().apply { targetSdkVersion = ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD }
        whenever(mockPackageManager.getApplicationInfo(testPackageName, 0)).doReturn(appInfo)
        whenever(mockPackageManager.getApplicationLabel(any())).doReturn("Test App")
        whenever(mockCallingPackageProvider.get()).doReturn(testPackageName)
        baseIntent =
            Intent(context, ContactsPickerActivity::class.java).apply {
                action = Intent.ACTION_PICK
                type = ContactsContract.Contacts.CONTENT_TYPE
            }
        val successState =
            MutableStateFlow(
                ContactsListState.Success(
                    availableContactsGroups = TreeMap(),
                    selectedContacts = emptyContactsSelection(),
                    isMultiSelectEnabled = false,
                    callingAppName = null,
                    requestedMimeTypes = emptyList(),
                    showPrivacyBanner = false,
                )
            )
        whenever(mockViewModel.uiState).thenReturn(successState)
        whenever(mockViewModel.userState).thenReturn(MutableStateFlow(PickerUserState.Loading))
        whenever(mockViewModel.snackbarEvents).thenReturn(emptyFlow())
        whenever(
                mockViewModel.handleIntent(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyInt(),
                    eq(ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD),
                )
            )
            .thenReturn(true)
        whenever(
                mockViewModel.handleIntent(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyInt(),
                    eq(ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD - 1),
                )
            )
            .thenReturn(false)
        doNothing().whenever(mockViewModel).onDoneClicked()
        whenever(mockViewModel.pickerResultEvents).thenReturn(mockEventsFlow)
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun onCreate_setsHideOverlayWindows() {
        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)
        scenario.onActivity { activity ->
            val attrs = activity.window.attributes
            assertThat(
                    attrs.privateFlags and
                        WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
                )
                .isNotEqualTo(0)
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun intent_contactPickerFlagDisabled_throws() {
        assertThrows(RuntimeException::class.java) {
            ActivityScenario.launch<ContactsPickerActivity>(baseIntent)
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun topBarSearchText_isDisplayed() {
        ActivityScenario.launch<ContactsPickerActivity>(baseIntent)
        composeTestRule
            .onNodeWithText(
                context.getString(R.string.contacts_picker_top_bar_search_placeholder_hint)
            )
            .assertIsDisplayed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun whenSwipedDown_activityFinishes() {
        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)
        composeTestRule.onNodeWithTag(BOTTOM_SHEET_TEST_TAG).performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        scenario.onActivity { activity ->
            if (activity != null) {
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun whenActivityIsRecreated_bottomSheetIsStillVisible() {
        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)
        composeTestRule.onNodeWithTag(BOTTOM_SHEET_TEST_TAG).assertIsDisplayed()

        scenario.recreate()

        composeTestRule.onNodeWithTag(BOTTOM_SHEET_TEST_TAG).assertIsDisplayed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun decideOnHandlingIntent_returnsTrue_handlesInternally() {
        // target SDK of calling app set to 37 in setUp
        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        scenario.onActivity { activity -> assertThat(activity.isFinishing).isFalse() }
        assertThat(Intents.getIntents().filter { it.action == Intent.ACTION_CHOOSER }).isEmpty()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun decideOnHandlingIntent_returnsFalse_forwardsToChooser() {
        val appInfo =
            ApplicationInfo().apply {
                targetSdkVersion = ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD - 1
            }
        whenever(mockPackageManager.getApplicationInfo(testPackageName, 0)).doReturn(appInfo)
        whenever(mockPackageManager.getPreferredActivities(any(), any(), any())).thenAnswer { 0 }

        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        Intents.intended(hasAction(Intent.ACTION_CHOOSER))
        assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun packageManagerThrowsException_handlesInternally() {
        whenever(mockPackageManager.getApplicationInfo(anyString(), anyInt()))
            .thenThrow(PackageManager.NameNotFoundException())

        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        composeTestRule
            .onNodeWithText(
                context.getString(R.string.contacts_picker_top_bar_search_placeholder_hint)
            )
            .assertIsDisplayed()

        scenario.onActivity { activity -> assertThat(activity.isFinishing).isFalse() }
        assertThat(Intents.getIntents().filter { it.action == Intent.ACTION_CHOOSER }).isEmpty()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun nullCallingPackage_finishesWithResultCanceled() {
        whenever(mockCallingPackageProvider.get()).doReturn(null)

        val scenario = ActivityScenario.launchActivityForResult<ContactsPickerActivity>(baseIntent)

        assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun handleDoneClicked_withSingleSelection_setsResultOkAndFinishes() = runTest {
        val successStateSingleSelect =
            MutableStateFlow(
                ContactsListState.Success(
                    availableContactsGroups =
                        ContactTestDataFactory.groupContactsForTest(listOf(testContact)),
                    selectedContacts = contactsSelectionOf(testContact.id, setOf(testContact.id)),
                    isMultiSelectEnabled = false,
                    callingAppName = null,
                    requestedMimeTypes = emptyList(),
                    showPrivacyBanner = false,
                )
            )
        whenever(mockViewModel.uiState).thenReturn(successStateSingleSelect)
        doAnswer {
                mockEventsFlow.tryEmit(
                    PickerResultEvent.SetResultAndFinish(
                        Intent().apply {
                            data = testUri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                )
                null
            }
            .`when`(mockViewModel)
            .onDoneClicked()

        val scenario = ActivityScenario.launchActivityForResult<ContactsPickerActivity>(baseIntent)

        composeTestRule.onNodeWithText("Done").performClick()

        composeTestRule.awaitIdle()

        val result = scenario.result
        assertThat(result.resultCode).isEqualTo(Activity.RESULT_OK)

        val resultIntent = result.resultData
        assertThat(resultIntent).isNotNull()
        assertThat(resultIntent.data).isEqualTo(testUri)
        assertThat(resultIntent.clipData).isNull()
        assertThat(resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(1)

        scenario.onActivity { activity ->
            if (activity != null) {
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun handleDoneClicked_withMultiSelection_setsResultOkWithClipData() {
        val successStateMultiSelect =
            MutableStateFlow(
                ContactsListState.Success(
                    availableContactsGroups =
                        ContactTestDataFactory.groupContactsForTest(listOf(testContact)),
                    selectedContacts = contactsSelectionOf(testContact.id, setOf(testContact.id)),
                    isMultiSelectEnabled = true,
                    callingAppName = null,
                    requestedMimeTypes = emptyList(),
                    showPrivacyBanner = false,
                )
            )
        whenever(mockViewModel.uiState).thenReturn(successStateMultiSelect)
        doAnswer {
                mockEventsFlow.tryEmit(
                    PickerResultEvent.SetResultAndFinish(
                        Intent().apply {
                            clipData =
                                ClipData.newUri(context.contentResolver, "uri", testUri).apply {
                                    addItem(ClipData.Item(testUri2))
                                }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                )
                null
            }
            .`when`(mockViewModel)
            .onDoneClicked()

        val scenario = ActivityScenario.launchActivityForResult<ContactsPickerActivity>(baseIntent)

        composeTestRule.onNodeWithText("Done").performClick()

        val result = scenario.result
        assertThat(result.resultCode).isEqualTo(Activity.RESULT_OK)

        val resultIntent = result.resultData as Intent

        assertThat(resultIntent).isNotNull()
        assertThat(resultIntent.data).isNull()
        assertThat(resultIntent.clipData).isNotNull()
        assertThat(resultIntent.clipData!!.itemCount).isEqualTo(2)
        assertThat(resultIntent.clipData!!.getItemAt(0).uri).isEqualTo(testUri)
        assertThat(resultIntent.clipData!!.getItemAt(1).uri).isEqualTo(testUri2)
        assertThat(resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(1)
        scenario.onActivity { activity ->
            if (activity != null) {
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun handleDoneClicked_viewModelReturnsNull_setsResultCanceled() {
        val successStateSingleSelect =
            MutableStateFlow(
                ContactsListState.Success(
                    availableContactsGroups =
                        ContactTestDataFactory.groupContactsForTest(listOf(testContact)),
                    selectedContacts = contactsSelectionOf(testContact.id, setOf(testContact.id)),
                    isMultiSelectEnabled = false,
                    callingAppName = null,
                    requestedMimeTypes = emptyList(),
                    showPrivacyBanner = false,
                )
            )
        whenever(mockViewModel.uiState).thenReturn(successStateSingleSelect)
        doAnswer {
                mockEventsFlow.tryEmit(PickerResultEvent.CancelAndFinish)
                null
            }
            .`when`(mockViewModel)
            .onDoneClicked()

        val scenario = ActivityScenario.launchActivityForResult<ContactsPickerActivity>(baseIntent)

        composeTestRule.onNodeWithText("Done").performClick()

        val result = scenario.result
        assertThat(result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
        scenario.onActivity { activity ->
            if (activity != null) {
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    @RequiresFlagsDisabled(FLAG_ENABLE_ACTION_PICK_TAKEOVER_IN_DROIDFOOD)
    fun lowTargetSdk_withPreferredActivity_startsPreferredActivity() {
        val appInfo =
            ApplicationInfo().apply {
                targetSdkVersion = ACTION_PICK_TAKEOVER_TARGET_SDK_THRESHOLD - 1
            }
        whenever(mockPackageManager.getApplicationInfo(testPackageName, 0)).doReturn(appInfo)
        val preferredComponent =
            ComponentName("com.preferred.app", "com.preferred.app.PickerActivity")
        Intents.intending(hasComponent(preferredComponent))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        whenever(mockPackageManager.getPreferredActivities(any(), any(), anyOrNull())).thenAnswer {
            val filters = it.getArgument<MutableList<IntentFilter>>(0)
            val activities = it.getArgument<MutableList<ComponentName>>(1)
            val filter = IntentFilter(Intent.ACTION_PICK)
            filter.addCategory(Intent.CATEGORY_DEFAULT)
            filter.addDataType(ContactsContract.Contacts.CONTENT_TYPE)
            filters.add(filter)
            activities.add(preferredComponent)
            1
        }

        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        Intents.intended(hasComponent(preferredComponent))
        assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun privacyBanner_showsAppName_andRetainsOnRotation() = runTest {
        val testAppName = "Test App"
        val successState =
            MutableStateFlow(
                ContactsListState.Success(
                    availableContactsGroups =
                        ContactTestDataFactory.groupContactsForTest(listOf(testContact)),
                    selectedContacts = emptyContactsSelection(),
                    isMultiSelectEnabled = false,
                    callingAppName = testAppName,
                    requestedMimeTypes = emptyList(),
                    showPrivacyBanner = true,
                )
            )
        whenever(mockViewModel.uiState).thenReturn(successState)

        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        composeTestRule.awaitIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_banner_description, testAppName))
            .assertIsDisplayed()

        scenario.recreate()

        composeTestRule.awaitIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_banner_description, testAppName))
            .assertIsDisplayed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun privacyDetailsPage_showsAppName_andRetainsOnRotation() = runTest {
        val testAppName = "Test App"
        val initialState =
            MutableStateFlow<ContactsUiState>(
                ContactsListState.Success(
                    availableContactsGroups =
                        ContactTestDataFactory.groupContactsForTest(listOf(testContact)),
                    selectedContacts = emptyContactsSelection(),
                    isMultiSelectEnabled = false,
                    callingAppName = testAppName,
                    requestedMimeTypes = emptyList(),
                    showPrivacyBanner = true,
                )
            )
        whenever(mockViewModel.uiState).thenReturn(initialState)
        doAnswer {
                initialState.value =
                    PrivacyDetailsState(
                        callingAppName = testAppName,
                        requestedMimeTypes = emptyList(),
                    )
                null
            }
            .whenever(mockViewModel)
            .onPrivacyDetailsBannerClicked()

        val scenario = ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_banner_more_details))
            .performClick()

        composeTestRule.awaitIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_details_description, testAppName))
            .assertIsDisplayed()

        scenario.recreate()

        composeTestRule.awaitIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_details_description, testAppName))
            .assertIsDisplayed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun privacyDetailsPage_backButton_returnsToPicker() = runTest {
        val testAppName = "Test App"
        val initialState =
            ContactsListState.Success(
                availableContactsGroups =
                    ContactTestDataFactory.groupContactsForTest(listOf(testContact)),
                selectedContacts = emptyContactsSelection(),
                isMultiSelectEnabled = false,
                callingAppName = testAppName,
                requestedMimeTypes = emptyList(),
                showPrivacyBanner = true,
            )
        val uiStateFlow = MutableStateFlow<ContactsUiState>(initialState)
        whenever(mockViewModel.uiState).thenReturn(uiStateFlow)
        doAnswer {
                uiStateFlow.value =
                    PrivacyDetailsState(
                        callingAppName = testAppName,
                        requestedMimeTypes = emptyList(),
                    )
                null
            }
            .whenever(mockViewModel)
            .onPrivacyDetailsBannerClicked()

        // Mock backward navigation
        doAnswer {
                uiStateFlow.value = initialState
                null
            }
            .whenever(mockViewModel)
            .onBackFromPrivacyDetails()

        ActivityScenario.launch<ContactsPickerActivity>(baseIntent)

        // Navigate to Privacy Details
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_banner_more_details))
            .performClick()
        composeTestRule.awaitIdle()

        // Click the Back Button
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.title_top_bar_back_button_content_description)
            )
            .performClick()
        composeTestRule.awaitIdle()

        // Verify we are successfully back on the original picker screen
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_banner_more_details))
            .assertIsDisplayed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    fun processIntent_throwsIllegalArgumentException_finishesWithResultCanceled() {
        whenever(
                mockViewModel.handleIntent(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyInt(),
                    anyInt(),
                )
            )
            .thenThrow(IllegalArgumentException("Missing requested fields"))

        val scenario = ActivityScenario.launchActivityForResult<ContactsPickerActivity>(baseIntent)

        assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    }
}
