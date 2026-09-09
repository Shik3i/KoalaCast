package net.koalastuff.koalacast.feature.account

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import net.koalastuff.koalacast.core.ui.theme.KoalaCastTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountAutofillTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val state = mutableStateOf(AccountUiState())

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            KoalaCastTheme {
                AccountContent(
                    state = state.value,
                    onBack = {},
                    onUsername = { state.value = state.value.copy(username = it) },
                    onPassword = { state.value = state.value.copy(password = it) },
                    onRecoveryCode = { state.value = state.value.copy(recoveryCodeInput = it) },
                    onNewPassword = { state.value = state.value.copy(newPassword = it) },
                    onRegister = {}, onLogin = {}, onRecover = {}, onRecoverySaved = {},
                    onLogout = {}, onSync = {}, onRevoke = {}, onGlobalStats = {},
                    onDeleteCredential = {}, onShowDeleteData = {}, onDeleteData = {},
                    onShowDelete = {}, onDeleteAccount = {}, onImport = {}, onExport = {},
                )
            }
        }
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun loginAutofillsBothEditableFields() {
        fill(ContentType.Username, "autofill-user")
        fill(ContentType.Password, "autofill-password")
        compose.runOnIdle {
            assertEquals("autofill-user", state.value.username)
            assertEquals("autofill-password", state.value.password)
        }
    }

    @Test
    fun registrationUsesNewCredentialsAndSwitchesBackToLogin() {
        selectMode(R.string.account_register)
        fill(ContentType.NewUsername, "new-user")
        fill(ContentType.NewPassword, "generated-password")
        compose.runOnIdle {
            assertEquals("new-user", state.value.username)
            assertEquals("generated-password", state.value.password)
        }
        field(ContentType.Password).assertDoesNotExist()
        selectMode(R.string.account_login)
        field(ContentType.NewUsername).assertDoesNotExist()
        field(ContentType.NewPassword).assertDoesNotExist()
        fill(ContentType.Username, "existing-user")
        fill(ContentType.Password, "existing-password")
        compose.runOnIdle {
            assertEquals("existing-user", state.value.username)
            assertEquals("existing-password", state.value.password)
        }
    }

    @Test
    fun recoveryFillsUsernameAndNewPasswordWithoutOverwritingRecoveryCode() {
        state.value = state.value.copy(recoveryCodeInput = "recovery-code")
        selectMode(R.string.account_recover)
        fill(ContentType.Username, "recovered-user")
        fill(ContentType.NewPassword, "replacement-password")
        compose.onNodeWithContentDescription(activity.get().getString(R.string.account_recovery_code))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentType))
        compose.runOnIdle {
            assertEquals("recovered-user", state.value.username)
            assertEquals("replacement-password", state.value.newPassword)
            assertEquals("recovery-code", state.value.recoveryCodeInput)
        }
    }

    private fun selectMode(label: Int) {
        compose.onNodeWithText(activity.get().getString(label)).performClick()
    }

    private fun field(type: ContentType) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.ContentType, type),
        useUnmergedTree = true,
    )

    private fun fill(type: ContentType, text: String) {
        field(type).performSemanticsAction(SemanticsActions.OnFillData) {
            it(requireNotNull(FillableData.createFromText(text)))
        }
    }
}
