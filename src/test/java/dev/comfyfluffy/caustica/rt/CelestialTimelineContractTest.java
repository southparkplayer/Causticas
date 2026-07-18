package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CelestialTimelineContractTest {
    @Test
    void equinoxSunFollowsThePhysicalLatitudeCircle() {
        AstronomicalSky.State noon = AstronomicalSky.calculate(0.0f, 0L, 0, 40.0f, 79);
        assertEquals(0.0f, noon.sunDirection()[0], 1.0e-5f);
        assertEquals(Math.cos(Math.toRadians(40.0)), noon.sunDirection()[1], 0.02);
        assertEquals(-Math.sin(Math.toRadians(40.0)), noon.sunDirection()[2], 0.02);
        assertEquals(0.0, noon.solarDeclination(), Math.toRadians(1.0));

        AstronomicalSky.State sunrise = AstronomicalSky.calculate((float)(-Math.PI * 0.5),
                0L, 0, 40.0f, 79);
        assertTrue(sunrise.sunDirection()[0] > 0.99f); // east
        assertEquals(0.0f, sunrise.sunDirection()[1], 0.02f);
    }

    @Test
    void seasonsTiltTheSunAndMoveTheCelestialPole() {
        AstronomicalSky.State june = AstronomicalSky.calculate(0.0f, 0L, 0, 40.0f, 171);
        AstronomicalSky.State december = AstronomicalSky.calculate(0.0f, 0L, 0, 40.0f, 354);
        assertEquals(Math.toRadians(23.44), june.solarDeclination(), Math.toRadians(0.5));
        assertEquals(Math.toRadians(-23.44), december.solarDeclination(), Math.toRadians(0.5));
        assertTrue(june.sunDirection()[1] > december.sunDirection()[1]);
        assertEquals(Math.sin(Math.toRadians(40.0)), june.celestialPole()[1], 1.0e-5);
        assertEquals(Math.cos(Math.toRadians(40.0)), june.celestialPole()[2], 1.0e-5);
    }

    @Test
    void lunarOrbitTracksPhaseWithoutBeingLockedOppositeTheSun() {
        AstronomicalSky.State full = AstronomicalSky.calculate((float)(-Math.PI * 0.5),
                0L, 0, 40.0f, 79);
        AstronomicalSky.State quarter = AstronomicalSky.calculate((float)(-Math.PI * 0.5),
                0L, 2, 40.0f, 79);
        AstronomicalSky.State newMoon = AstronomicalSky.calculate((float)(-Math.PI * 0.5),
                0L, 4, 40.0f, 79);

        float fullAlignment = dot(full.sunDirection(), full.moonDirection());
        assertTrue(fullAlignment < -0.99f, "full-moon solar alignment=" + fullAlignment);
        assertEquals(1.0f, full.moonLitFraction(), 1.0e-5f);
        assertEquals(0.5f, quarter.moonLitFraction(), 1.0e-5f);
        float newAlignment = dot(newMoon.sunDirection(), newMoon.moonDirection());
        assertTrue(newAlignment > 0.99f, "new-moon solar alignment=" + newAlignment);
        assertEquals(0.0f, newMoon.moonLitFraction(), 1.0e-5f);
        assertTrue(Math.abs(full.lunarDeclination() + full.solarDeclination()) > 1.0e-4f,
                "inclined lunar orbit must not collapse to the old exact antipode");
    }

    @Test
    void solarAltitudeOwnsDayTwilightAndStars() {
        AstronomicalSky.State noon = AstronomicalSky.calculate(0.0f, 0L, 0, 0.0f, 79);
        AstronomicalSky.State midnight = AstronomicalSky.calculate((float)Math.PI, 0L, 0, 0.0f, 79);
        assertEquals(1.0f, noon.dayFactor(), 1.0e-5f);
        assertEquals(1.0f, noon.solarEnvelope(), 1.0e-5f);
        assertEquals(0.0f, noon.starBrightness(), 1.0e-5f);
        assertEquals(0.0f, midnight.dayFactor(), 1.0e-5f);
        assertEquals(0.0f, midnight.solarEnvelope(), 1.0e-5f);
        assertEquals(1.0f, midnight.starBrightness(), 1.0e-5f);
    }

    @Test
    void lunarDiscVisibilityCrossesTheHorizonContinuously() {
        float radius = (float)Math.toRadians(0.2727);
        assertEquals(0.0f, AstronomicalSky.lunarDiscHorizonVisibility((float)Math.sin(-radius), radius),
                1.0e-5f);
        assertEquals(0.5f, AstronomicalSky.lunarDiscHorizonVisibility(0.0f, radius), 1.0e-5f);
        assertEquals(1.0f, AstronomicalSky.lunarDiscHorizonVisibility((float)Math.sin(radius), radius),
                1.0e-5f);
        float low = AstronomicalSky.lunarDiscHorizonVisibility((float)Math.sin(-0.5f * radius), radius);
        float high = AstronomicalSky.lunarDiscHorizonVisibility((float)Math.sin(0.5f * radius), radius);
        assertTrue(low > 0.0f && low < 0.5f);
        assertEquals(1.0f, low + high, 1.0e-5f);
    }

    @Test
    void lunarIlluminanceIsCalibratedByPhaseAndVisibility() {
        assertEquals(0.25f, RtComposite.lunarIlluminanceLux(1.0f, 1.0f,
                0.0f, 1.0f, 1.0f, 1.0f), 1.0e-6f);
        assertEquals(0.125f, RtComposite.lunarIlluminanceLux(1.0f, 0.5f,
                0.0f, 1.0f, 1.0f, 1.0f), 1.0e-6f);
        assertEquals(0.0f, RtComposite.lunarIlluminanceLux(1.0f, 0.0f,
                0.0f, 1.0f, 1.0f, 1.0f), 1.0e-6f);
        assertEquals(0.0f, RtComposite.lunarIlluminanceLux(1.0f, 1.0f,
                0.0f, 1.0f, 0.0f, 1.0f), 1.0e-6f);
    }

    @Test
    void minecraftClockAndPhaseRemainTheOnlyTimelineInputs() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        for (String attribute : new String[] {"SUN_ANGLE", "MOON_PHASE", "SKY_COLOR"}) {
            assertTrue(source.contains("EnvironmentAttributes." + attribute));
        }
        for (String retiredTransform : new String[] {"MOON_ANGLE", "STAR_ANGLE", "STAR_BRIGHTNESS",
                "SUNRISE_SUNSET_COLOR", "vanillaCelestialDirection"}) {
            assertFalse(source.contains(retiredTransform));
        }
        assertTrue(source.contains("mc.level.getLevelData().getGameTime()"));
        assertTrue(source.contains("AstronomicalSky.calculate"));
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
