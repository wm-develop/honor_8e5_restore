package dev.honor.restore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalServiceInstrumentedTest {
    @Test
    fun anotherPackageCannotStartReleaseService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val intent = Intent().setComponent(ComponentName("wm.honor8e5restore", "dev.honor.restore.RestoreService"))
                .setAction("dev.honor.restore.audit.NOOP")
            val result = runCatching { context.startService(intent) }
            android.util.Log.e("RestoreAudit", "EXTERNAL_CALL package=${context.packageName} uid=${android.os.Process.myUid()} result=$result")
            val failure = result.exceptionOrNull()
            assertTrue("应拒绝启动或返回空组件：$result", failure is SecurityException || result.getOrNull() == null)
        }
    }
}
