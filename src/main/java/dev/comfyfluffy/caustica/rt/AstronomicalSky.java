package dev.comfyfluffy.caustica.rt;

/**
 * Small Earth-sky ephemeris used by the Overworld renderer. Minecraft's accelerated clock remains the
 * time authority, but the clock is projected onto a tilted Earth instead of rotating both bodies around
 * a horizontal axis. The solar declination series is NOAA's fractional-year approximation; the Moon is
 * a deliberately bounded visual ephemeris with the real mean ecliptic inclination and Minecraft's
 * eight-day phase cycle.
 */
final class AstronomicalSky {
    private static final double TAU = Math.PI * 2.0;
    private static final double DAYS_PER_YEAR = 365.2422;
    private static final double OBLIQUITY = Math.toRadians(23.439281);
    private static final double LUNAR_INCLINATION = Math.toRadians(5.145);
    private static final double LUNAR_NODE_PERIOD_DAYS = 18.6 * DAYS_PER_YEAR;

    private AstronomicalSky() {
    }

    record State(float[] sunDirection, float[] moonDirection, float[] celestialPole,
                 float solarHourAngle, float lunarHourAngle, float siderealAngle,
                 float solarDeclination, float lunarDeclination, float moonLitFraction,
                 float dayFactor, float twilightFactor, float solarEnvelope, float starBrightness) {
    }

    static State calculate(float solarClockAngle, long worldDay, int moonPhase,
                           float latitudeDegrees, int dayOfYearOffset) {
        double hourAngle = wrapPi(solarClockAngle);
        double latitude = Math.toRadians(Math.clamp(latitudeDegrees, -90.0f, 90.0f));
        int dayOfYear = Math.floorMod((int)Math.floorMod(worldDay, 365L) + dayOfYearOffset, 365) + 1;

        // NOAA fractional-year declination series. hourAngle/TAU is the fractional solar day from noon.
        double gamma = TAU / 365.0 * (dayOfYear - 1.0 + hourAngle / TAU);
        double solarDeclination = 0.006918
                - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2.0 * gamma) + 0.000907 * Math.sin(2.0 * gamma)
                - 0.002697 * Math.cos(3.0 * gamma) + 0.001480 * Math.sin(3.0 * gamma);

        float[] sun = horizontalDirection(latitude, solarDeclination, hourAngle);
        float[] pole = new float[] {0.0f, (float)Math.sin(latitude), (float)Math.cos(latitude)};

        // Ecliptic longitude is used only to establish right ascension/local sidereal time. Declination
        // above remains the more accurate NOAA series that owns the visible solar altitude.
        double fractionalDay = positiveModulo(hourAngle / TAU + 0.25, 1.0);
        // SUN_ANGLE arrives as a float. At exact sunrise its roundoff can land a few nanoseconds before
        // the modulo boundary, which must mean day fraction zero rather than almost one (a 45-degree
        // discontinuity in Minecraft's accelerated eight-day lunar cycle).
        if (fractionalDay > 1.0 - 1.0e-6) fractionalDay = 0.0;
        double solarLongitude = TAU * (dayOfYear - 80.0 + fractionalDay) / DAYS_PER_YEAR;
        double solarRightAscension = Math.atan2(Math.sin(solarLongitude) * Math.cos(OBLIQUITY),
                Math.cos(solarLongitude));
        double localSiderealAngle = wrapPi(hourAngle + solarRightAscension);

        // Interpolate between Minecraft's daily phase indices so the Moon advances continuously instead
        // of jumping 45 degrees when its atlas phase changes. Phase zero is full; phase four is new.
        double phaseTurns = Math.floorMod(moonPhase, 8) / 8.0 + fractionalDay / 8.0;
        double elongation = Math.PI + TAU * phaseTurns;
        double lunarLongitude = solarLongitude + elongation;
        double absoluteDay = worldDay + fractionalDay;
        double ascendingNode = Math.toRadians(125.04452) - TAU * absoluteDay / LUNAR_NODE_PERIOD_DAYS;
        double lunarLatitude = Math.asin(Math.sin(LUNAR_INCLINATION)
                * Math.sin(lunarLongitude - ascendingNode));

        double cosBeta = Math.cos(lunarLatitude);
        double eclipticX = cosBeta * Math.cos(lunarLongitude);
        double eclipticY = cosBeta * Math.sin(lunarLongitude);
        double eclipticZ = Math.sin(lunarLatitude);
        double equatorialX = eclipticX;
        double equatorialY = eclipticY * Math.cos(OBLIQUITY) - eclipticZ * Math.sin(OBLIQUITY);
        double equatorialZ = eclipticY * Math.sin(OBLIQUITY) + eclipticZ * Math.cos(OBLIQUITY);
        double lunarRightAscension = Math.atan2(equatorialY, equatorialX);
        double lunarDeclination = Math.asin(Math.clamp(equatorialZ, -1.0, 1.0));
        double lunarHourAngle = wrapPi(localSiderealAngle - lunarRightAscension);
        float[] moon = horizontalDirection(latitude, lunarDeclination, lunarHourAngle);

        float dayFactor = smoothstep(sineDegrees(-0.2666), sineDegrees(0.2666), sun[1]);
        float civilTwilight = smoothstep(sineDegrees(-6.0), sineDegrees(-0.2666), sun[1]);
        float twilightFactor = civilTwilight * (1.0f - dayFactor);
        float solarEnvelope = Math.max(dayFactor, civilTwilight);
        float starBrightness = 1.0f - smoothstep(sineDegrees(-12.0), sineDegrees(-6.0), sun[1]);
        float moonLitFraction = (float)(0.5 * (1.0 + Math.cos(TAU * phaseTurns)));

        return new State(sun, moon, pole, (float)hourAngle, (float)lunarHourAngle,
                (float)localSiderealAngle, (float)solarDeclination, (float)lunarDeclination,
                moonLitFraction, dayFactor, twilightFactor, solarEnvelope, starBrightness);
    }

    private static float[] horizontalDirection(double latitude, double declination, double hourAngle) {
        double cosDeclination = Math.cos(declination);
        double east = -cosDeclination * Math.sin(hourAngle);
        double up = Math.sin(latitude) * Math.sin(declination)
                + Math.cos(latitude) * cosDeclination * Math.cos(hourAngle);
        double north = Math.cos(latitude) * Math.sin(declination)
                - Math.sin(latitude) * cosDeclination * Math.cos(hourAngle);
        return new float[] {(float)east, (float)up, (float)north};
    }

    private static float sineDegrees(double degrees) {
        return (float)Math.sin(Math.toRadians(degrees));
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static double wrapPi(double angle) {
        double wrapped = angle % TAU;
        if (wrapped <= -Math.PI) wrapped += TAU;
        if (wrapped > Math.PI) wrapped -= TAU;
        return wrapped;
    }

    private static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }
}
