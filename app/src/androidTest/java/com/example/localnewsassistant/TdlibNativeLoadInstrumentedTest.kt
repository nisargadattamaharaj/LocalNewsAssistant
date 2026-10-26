package com.example.localnewsassistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localnewsassistant.tdlib.TdlibNativeLoadProbe
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TdlibNativeLoadInstrumentedTest {

    private companion object {
        const val EXPECTED_COMMIT_HASH = "ea3f69ce11aef458bfbb09869b809ede4447486f"
        const val TAG = "TdlibNativeLoadTest"
    }

    @Test
    fun testNativeTdlibLibraryLoadAndJniOnLoadRegistration(): Unit {
        val commitField = TdApi::class.java.getDeclaredField("GIT_COMMIT_HASH")
        commitField.isAccessible = true
        val actualCommit = commitField.get(null) as String
        assertEquals(
            "TdApi.GIT_COMMIT_HASH must match pinned TDLib commit",
            EXPECTED_COMMIT_HASH,
            actualCommit
        )

        val loaded = TdlibNativeLoadProbe.load()
        assertTrue("TdlibNativeLoadProbe.load() must return true", loaded)

        Log.i(TAG, "TDLIB_NATIVE_LOAD_104_OK: commit=$actualCommit")
    }
}
