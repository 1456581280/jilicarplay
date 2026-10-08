package com.shilapi.xcertplay

import android.app.job.JobScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalOnlyProfilesTest {
    @Test fun savingKeepsBindingsWithoutAnOutboxOrScheduledUpload() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        val profile = SteeringProfile("Test vehicle", "Test head unit",
            bindings = listOf(SteeringBinding("play_pause", 85, 0, source = "vehicle_bridge")))
        SteeringProfiles.save(context, profile)
        assertEquals(profile, SteeringProfiles.loadEnabled(context))
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertFalse(File(context.filesDir, "steering-profiles/outbox").exists())
        assertFalse(File(context.filesDir, "vehicle-report-outbox").exists())
        SteeringProfiles.disable(context)
        assertNull(SteeringProfiles.loadEnabled(context))
        assertEquals(profile, SteeringProfiles.load(context))
    }
}
