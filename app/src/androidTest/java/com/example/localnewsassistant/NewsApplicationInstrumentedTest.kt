package com.example.localnewsassistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsApplicationInstrumentedTest {

    @Test
    fun testTargetApplicationIsLocalNewsApplication() {
        val providerContext: Context = ApplicationProvider.getApplicationContext()
        assertTrue(
            "ApplicationContext from ApplicationProvider should be an instance of LocalNewsApplication, but was: ${providerContext.javaClass.name}",
            providerContext is LocalNewsApplication
        )

        val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertSame(
            "ApplicationContext from InstrumentationRegistry targetContext should be the same instance as ApplicationProvider context",
            providerContext,
            targetContext
        )

        val secondProviderContext: Context = ApplicationProvider.getApplicationContext()
        assertSame(
            "Repeated ApplicationProvider.getApplicationContext() calls should return the same instance",
            providerContext,
            secondProviderContext
        )
    }
}
