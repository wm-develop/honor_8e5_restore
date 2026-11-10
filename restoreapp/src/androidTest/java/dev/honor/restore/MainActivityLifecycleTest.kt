package dev.honor.restore

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.honor.restore.core.OtaHistoryMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {
    @Test
    fun initialDialogAndSelectedModeSurviveRecreationAndBackgrounding() {
        RestoreRuntime.state.value = RestoreUiState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(OtaHistoryMode.UNKNOWN, RestoreRuntime.state.value.historyMode)
                assertEquals(false, activity.isFinishing)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                RestoreRuntime.startHistory(OtaHistoryMode.MULTIPLE)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(OtaHistoryMode.MULTIPLE, RestoreRuntime.state.value.historyMode)
                assertEquals(false, activity.isFinishing)
            }
        }
    }
}
