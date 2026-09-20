package com.kiwinokoto.powerwatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorPrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("powerwatch", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun backendHealthIsUnknownByDefault() {
        assertEquals(BackendHealth.UNKNOWN, MonitorPrefs.backendHealth(context))
        assertNull(MonitorPrefs.backendLastContact(context))
    }

    @Test
    fun httpResultTracksBackendHealthAndContact() {
        MonitorPrefs.recordBackendHttpResult(context, 204)
        assertEquals(BackendHealth.OK, MonitorPrefs.backendHealth(context))
        assertNotNull(MonitorPrefs.backendLastContact(context))

        MonitorPrefs.recordBackendHttpResult(context, 503)
        assertEquals(BackendHealth.ERROR, MonitorPrefs.backendHealth(context))
        assertNotNull(MonitorPrefs.backendLastContact(context))
    }

    @Test
    fun unreachableKeepsLastContactTimestamp() {
        MonitorPrefs.recordBackendHttpResult(context, 200)
        val contact = MonitorPrefs.backendLastContact(context)

        MonitorPrefs.markBackendUnreachable(context)

        assertEquals(BackendHealth.UNREACHABLE, MonitorPrefs.backendHealth(context))
        assertEquals(contact, MonitorPrefs.backendLastContact(context))
    }

    @Test
    fun changingWebhookResetsBackendState() {
        MonitorPrefs.setWebhookUrl(context, "https://one.example/api/v1/events")
        MonitorPrefs.recordBackendHttpResult(context, 200)

        MonitorPrefs.setWebhookUrl(context, "https://two.example/api/v1/events")

        assertEquals(BackendHealth.UNKNOWN, MonitorPrefs.backendHealth(context))
        assertNull(MonitorPrefs.backendLastContact(context))
    }
}
