package app.motif

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The account screens without a server: the welcome screen, skipping to offline,
 * and Settings with its history and server pages while signed out.
 */
@RunWith(AndroidJUnit4::class)
class AccountFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<MotifApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun skipSignInKeepsTheAppWorking() {
        instrumentation.runOnMainSync { app.account.showWelcome() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Sign in to sync your listening")).fetchSemanticsNodes().isNotEmpty() }
        Screenshots.take("account-welcome")

        compose.onNodeWithText("Create an account").performClick()
        compose.onNodeWithText("Confirm password").assertExists()
        Screenshots.take("account-sign-up")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Skip, use Motif offline").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Continue offline")).fetchSemanticsNodes().isNotEmpty() }
        Screenshots.take("account-offline")
        compose.onNodeWithText("Continue offline").performClick()

        compose.waitUntil(5_000) { !app.account.showWelcome.value }
        compose.onNodeWithContentDescription("Add music").assertExists()
        assertFalse(app.account.state.value.signedIn)
    }

    @Test fun settingsWhileSignedOut() {
        instrumentation.runOnMainSync { app.account.continueOffline() }
        compose.onNodeWithContentDescription("Settings and account").performClick()
        compose.onNodeWithText("Not signed in").assertExists()
        Screenshots.take("account-settings")

        compose.onNodeWithText("Listening history").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No plays yet", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        Screenshots.take("account-history")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Server").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("motif-server.vercel.app", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        Screenshots.take("account-server")
    }
}
