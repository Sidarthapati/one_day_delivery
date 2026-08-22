package com.oneday.vision;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.Dictionary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.line;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;

/**
 * Not a test — a one-off generator for the printable 5cm ArUco reference marker the DA places on the
 * parcel. Enabled only when {@code -Dmarker.out=<path.png>} is passed, so it never runs in CI.
 * DICT_4X4_50 id 0 at 300 DPI, with a quiet zone, a caption, and a 5cm scale bar to verify print size.
 */
@EnabledIfSystemProperty(named = "marker.out", matches = ".+")
class MarkerBoardGeneratorTest {

    private static final int DPI = 300;
    private static final double MARKER_CM = 5.0;

    @Test
    void generate() {
        String out = System.getProperty("marker.out");
        int markerPx = (int) Math.round(MARKER_CM / 2.54 * DPI);   // 5cm @300dpi ≈ 591px
        int quiet = markerPx / 4;                                   // white quiet zone
        int w = markerPx + 2 * quiet;
        int h = markerPx + 2 * quiet + 320;                        // extra space for caption + scale bar

        Mat canvas = new Mat(h, w, CV_8UC3, new Scalar(255, 255, 255, 0));

        Dictionary dict = org.bytedeco.opencv.global.opencv_objdetect.getPredefinedDictionary(4); // DICT_5X5_50
        Mat gray = new Mat();
        org.bytedeco.opencv.global.opencv_objdetect.generateImageMarker(dict, 0, markerPx, gray, 1);
        Mat bgr = new Mat();
        cvtColor(gray, bgr, COLOR_GRAY2BGR);
        bgr.copyTo(new Mat(canvas, new Rect(quiet, quiet, markerPx, markerPx)));

        int textY = quiet + markerPx + 70;
        putText(canvas, "Godspeed - Parcel Dimension Marker (5.0 cm)", new Point(quiet, textY),
                FONT_HERSHEY_SIMPLEX, 0.9, new Scalar(0, 0, 0, 0), 2, LINE_8, false);
        putText(canvas, "PRINT AT 100% (no scaling / no fit-to-page).", new Point(quiet, textY + 45),
                FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(0, 0, 0, 0), 2, LINE_8, false);
        putText(canvas, "Lay flat on the parcel face; shoot straight on.", new Point(quiet, textY + 85),
                FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(0, 0, 0, 0), 2, LINE_8, false);

        // 5cm scale bar (should measure exactly 5cm with a ruler after printing).
        int barY = textY + 150;
        int barX0 = quiet;
        int barX1 = quiet + markerPx;   // markerPx == 5cm
        line(canvas, new Point(barX0, barY), new Point(barX1, barY), new Scalar(0, 0, 0, 0), 3, LINE_8, 0);
        line(canvas, new Point(barX0, barY - 12), new Point(barX0, barY + 12), new Scalar(0, 0, 0, 0), 3, LINE_8, 0);
        line(canvas, new Point(barX1, barY - 12), new Point(barX1, barY + 12), new Scalar(0, 0, 0, 0), 3, LINE_8, 0);
        putText(canvas, "5.0 cm - verify with a ruler", new Point(barX0, barY + 45),
                FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(0, 0, 0, 0), 1, LINE_8, false);

        Assumptions.assumeTrue(imwrite(out, canvas), "imwrite failed");
    }
}
