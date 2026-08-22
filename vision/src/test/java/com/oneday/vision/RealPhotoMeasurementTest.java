package com.oneday.vision;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Diagnostic run against a REAL photo (e.g. an object beside an ArUco marker on a table, coplanar).
 * Since a marker's dictionary/size may be unknown, this sweeps the common predefined dictionaries and
 * prints what each detects/measures (assuming a 5cm marker). Not a strict assertion test — it's the
 * "does the pipeline work on a real image" check ahead of the physical spike. Run with
 * {@code -Dphoto.in=/path/to/photo.jpg}; skipped otherwise.
 */
@EnabledIfSystemProperty(named = "photo.in", matches = ".+")
class RealPhotoMeasurementTest {

    private static final Path IMG = Path.of(System.getProperty("photo.in", ""));

    // Common OpenCV predefined dictionary ordinals to try.
    private static final int[] DICTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 17, 20};
    private static final String[] NAMES = {
            "4X4_50", "4X4_100", "4X4_250", "4X4_1000", "5X5_50", "5X5_100", "5X5_250", "5X5_1000",
            "6X6_50", "6X6_100", "6X6_250", "6X6_1000", "7X7_50", "ARUCO_ORIGINAL", "APRILTAG_16h5", "APRILTAG_36h11"};

    @Test
    void sweepDictionariesAndMeasure() throws Exception {
        Assumptions.assumeTrue(Files.exists(IMG), "real photo not present: " + IMG);
        byte[] bytes = Files.readAllBytes(IMG);

        VisionProperties baseProps = new VisionProperties();
        Assumptions.assumeTrue(new OpenCvArucoDimensionEngine(baseProps).isAvailable(),
                "OpenCV native lib not available");

        System.out.println("\n=== Real-photo dictionary sweep (marker assumed 5.0 cm) ===");
        for (int i = 0; i < DICTS.length; i++) {
            VisionProperties props = new VisionProperties();
            props.setDictionaryId(DICTS[i]);
            props.setMarkerSizeCm(5.0);
            props.setMinConfidence(0.0);   // don't filter — we want to see the raw result
            OpenCvArucoDimensionEngine engine = new OpenCvArucoDimensionEngine(props);
            MeasurementResult r = engine.measure(List.of(
                    new DimensionEngine.Capture(bytes, DimensionEngine.View.TOP)));
            System.out.printf("dict %-16s (id=%2d) -> %-16s L=%s W=%s conf=%.2f  [%s]%n",
                    NAMES[i], DICTS[i], r.status(),
                    fmt(r.lengthCm()), fmt(r.widthCm()), r.confidence(), r.detail());
        }
        System.out.println("=== end sweep ===\n");
    }

    private static String fmt(Double d) {
        return d == null ? "  -  " : String.format("%.1fcm", d);
    }
}
