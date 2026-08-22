package com.oneday.vision;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_objdetect.Dictionary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.FILLED;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;

/**
 * Validates the measurement math on a synthetic, perspective-free scene: a generated ArUco marker of
 * known pixel size (= the reference scale) plus a rectangle of known cm dimensions on the same plane.
 * The engine must recover the rectangle's real size from the marker scale. This exercises marker
 * detection + homography + contour measurement without needing physical parcel photos (that is the
 * Phase-0 caliper spike). Skipped automatically if the native OpenCV lib can't load on this host.
 */
class OpenCvArucoDimensionEngineTest {

    private static final double PX_PER_CM = 20.0;   // scale of the synthetic scene
    private OpenCvArucoDimensionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OpenCvArucoDimensionEngine(new VisionProperties()); // markerSizeCm=5, dict=DICT_4X4_50
        Assumptions.assumeTrue(engine.isAvailable(), "OpenCV native lib not available on this host");
    }

    @Test
    void measuresKnownRectangleFromMarkerScale() {
        // A 30cm x 20cm parcel top face with the 5cm marker placed on it.
        byte[] png = syntheticTopFace(30, 20);
        MeasurementResult r = engine.measure(List.of(new DimensionEngine.Capture(png, DimensionEngine.View.TOP)));

        assertThat(r.status()).isEqualTo(MeasurementResult.Status.OK);
        assertThat(r.lengthCm()).isCloseTo(30.0, org.assertj.core.data.Offset.offset(1.5));
        assertThat(r.widthCm()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(1.5));
        assertThat(r.heightCm()).isNull();               // top-only, no side photo
        assertThat(r.confidence()).isGreaterThan(0.8);
    }

    @Test
    void derivesHeightFromTopPlusSide() {
        byte[] top = syntheticTopFace(30, 20);           // length 30, width 20
        byte[] side = syntheticTopFace(30, 12);          // side face shares the 30 edge; height 12
        MeasurementResult r = engine.measure(List.of(
                new DimensionEngine.Capture(top, DimensionEngine.View.TOP),
                new DimensionEngine.Capture(side, DimensionEngine.View.SIDE)));

        assertThat(r.status()).isEqualTo(MeasurementResult.Status.OK);
        assertThat(r.lengthCm()).isCloseTo(30.0, org.assertj.core.data.Offset.offset(1.5));
        assertThat(r.widthCm()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(1.5));
        assertThat(r.heightCm()).isCloseTo(12.0, org.assertj.core.data.Offset.offset(1.5));
    }

    @Test
    void noMarkerReturnsNoMarker() {
        byte[] png = blankCanvas();
        MeasurementResult r = engine.measure(List.of(new DimensionEngine.Capture(png, DimensionEngine.View.TOP)));
        assertThat(r.status()).isEqualTo(MeasurementResult.Status.NO_MARKER);
    }

    @Test
    void emptyInputReturnsBadInput() {
        assertThat(engine.measure(List.of()).status()).isEqualTo(MeasurementResult.Status.BAD_INPUT);
    }

    // ── synthetic scene helpers ───────────────────────────────────────────────

    /** White canvas with a gray rectangle of the given cm size (black border) + a 5cm marker on it. */
    private byte[] syntheticTopFace(int lengthCm, int widthCm) {
        int margin = 120;
        int rectW = (int) (lengthCm * PX_PER_CM);
        int rectH = (int) (widthCm * PX_PER_CM);
        Mat canvas = new Mat(rectH + 2 * margin, rectW + 2 * margin, CV_8UC3, new Scalar(255, 255, 255, 0));

        // Parcel top face: filled gray rect + a dark border so Canny finds a clean quad.
        Point tl = new Point(margin, margin);
        Point br = new Point(margin + rectW, margin + rectH);
        rectangle(canvas, tl, br, new Scalar(200, 200, 200, 0), FILLED, 8, 0);
        rectangle(canvas, tl, br, new Scalar(0, 0, 0, 0), 3, 8, 0);

        // 5cm marker placed on the face (inside the rect, so it's excluded from the parcel contour).
        int side = (int) (5 * PX_PER_CM);
        Mat marker = markerImage(side);
        int mx = margin + rectW / 2 - side / 2;
        int my = margin + rectH / 2 - side / 2;
        Mat roi = new Mat(canvas, new Rect(mx, my, side, side));
        marker.copyTo(roi);
        return encode(canvas);
    }

    private byte[] blankCanvas() {
        return encode(new Mat(600, 800, CV_8UC3, new Scalar(255, 255, 255, 0)));
    }

    private Mat markerImage(int sidePx) {
        Dictionary dict = org.bytedeco.opencv.global.opencv_objdetect.getPredefinedDictionary(4); // DICT_5X5_50
        Mat gray = new Mat();
        org.bytedeco.opencv.global.opencv_objdetect.generateImageMarker(dict, 0, sidePx, gray, 1);
        Mat bgr = new Mat();
        cvtColor(gray, bgr, COLOR_GRAY2BGR);
        return bgr;
    }

    private byte[] encode(Mat img) {
        BytePointer buf = new BytePointer();
        imencode(".png", img, buf);
        byte[] out = new byte[(int) buf.limit()];
        buf.get(out);
        return out;
    }
}
