package com.cseini.byd.karaoke

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class DrivingGuardTest {
    private val ok = DrivingGuard.Verdict.OK
    private val drive = DrivingGuard.Verdict.DRIVING
    private val unknown = DrivingGuard.Verdict.UNKNOWN

    @Test fun parkedAndNeutralAllowed() {
        assertEquals(ok, DrivingGuard.decide(DrivingGuard.GEAR_P, 0.0))
        assertEquals(ok, DrivingGuard.decide(DrivingGuard.GEAR_N, 0.0))
        assertEquals(ok, DrivingGuard.decide(DrivingGuard.GEAR_P, null))
    }

    @Test fun driveAndReverseBlocked() {
        assertEquals(drive, DrivingGuard.decide(DrivingGuard.GEAR_D, 0.0))
        assertEquals(drive, DrivingGuard.decide(DrivingGuard.GEAR_R, 0.0))
        assertEquals(drive, DrivingGuard.decide(DrivingGuard.GEAR_D, null))
    }

    @Test fun unknownGearAndSpeedBlocked() {
        assertEquals(unknown, DrivingGuard.decide(null, null))
    }

    @Test fun unknownGearFallsBackToSpeed() {
        assertEquals(ok, DrivingGuard.decide(null, 0.0))
        assertEquals(ok, DrivingGuard.decide(null, 4.9))
        assertEquals(drive, DrivingGuard.decide(null, 5.0))
        assertEquals(drive, DrivingGuard.decide(null, 60.0))
    }

    @Test fun movingBlocksEvenIfGearSaysParkedOrNeutral() {
        assertEquals(drive, DrivingGuard.decide(DrivingGuard.GEAR_P, 30.0))
        assertEquals(drive, DrivingGuard.decide(DrivingGuard.GEAR_N, 5.0))
        assertEquals(ok, DrivingGuard.decide(DrivingGuard.GEAR_N, 4.9))
    }

    @Test fun d5GearMapping() {
        assertEquals(DrivingGuard.GEAR_P, DrivingGuard.mapD5Gear(1))
        assertEquals(DrivingGuard.GEAR_R, DrivingGuard.mapD5Gear(2))
        assertEquals(DrivingGuard.GEAR_N, DrivingGuard.mapD5Gear(3))
        assertEquals(DrivingGuard.GEAR_D, DrivingGuard.mapD5Gear(4))
        assertNull(DrivingGuard.mapD5Gear(0))
        assertNull(DrivingGuard.mapD5Gear(5))
    }
}
