package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ParticleAqiTest {
    @Test public void usesCurrentFineParticleBreakpoints() {
        assertEquals(50, ParticleAqi.fine(9.0));
        assertEquals(51, ParticleAqi.fine(9.1));
        assertEquals(100, ParticleAqi.fine(35.4));
        assertEquals(101, ParticleAqi.fine(35.5));
        assertEquals(200, ParticleAqi.fine(125.4));
        assertEquals(300, ParticleAqi.fine(225.4));
        assertEquals(500, ParticleAqi.fine(325.4));
    }
    @Test public void truncatesConcentrationsBeforeInterpolation() {
        assertEquals(50, ParticleAqi.fine(9.09));
        assertEquals(50, ParticleAqi.coarse(54.99));
        assertEquals(51, ParticleAqi.coarse(55));
    }
    @Test public void higherParticleSubIndexWins() {
        assertEquals(77, ParticleAqi.combined(23, 41));
        assertEquals(200, ParticleAqi.combined(0, 354));
    }
    @Test public void higherThanScaleIsNotMisreportedAs500() {
        assertTrue(ParticleAqi.fine(400) > 500);
        assertTrue(ParticleAqi.coarse(700) > 500);
    }
    @Test(expected=IllegalArgumentException.class) public void missingFineReadingIsRejected() {
        ParticleAqi.combined(Double.NaN, 41);
    }
    @Test(expected=IllegalArgumentException.class) public void negativeReadingIsRejected() {
        ParticleAqi.coarse(-1);
    }
}
