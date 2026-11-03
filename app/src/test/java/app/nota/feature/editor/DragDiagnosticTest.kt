package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** THROWAWAY diagnostic #4 — does a tall display actually apply? */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragDiagnosticTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun measure(tag: String): String {
        composeRule.setContent { Box(Modifier.fillMaxSize()) }
        composeRule.waitForIdle()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dm = ctx.resources.displayMetrics
        val decor = composeRule.activity.window.decorView
        return "$tag rootH=${root.bottom - root.top} rootW=${root.right - root.left} " +
            "displayMetrics=${dm.widthPixels}x${dm.heightPixels} density=${dm.density} " +
            "decor=${decor.width}x${decor.height}"
    }

    @Test
    fun heightFromConfigQualifiers() {
        fail("DIAG4-CONFIG " + measure("config"))
    }

    @Test
    fun heightFromRuntimeQualifiers() {
        RuntimeEnvironment.setQualifiers("+w411dp-h1000dp")
        fail("DIAG4-RUNTIME " + measure("runtime"))
    }
}
