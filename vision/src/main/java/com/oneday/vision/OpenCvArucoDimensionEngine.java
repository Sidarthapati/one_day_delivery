package com.oneday.vision;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.ArucoDetector;
import org.bytedeco.opencv.opencv_objdetect.DetectorParameters;
import org.bytedeco.opencv.opencv_objdetect.Dictionary;
import org.bytedeco.opencv.opencv_objdetect.RefineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.perspectiveTransform;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imdecode;
import static org.bytedeco.opencv.global.opencv_imgproc.CHAIN_APPROX_SIMPLE;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.Canny;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.RETR_EXTERNAL;
import static org.bytedeco.opencv.global.opencv_imgproc.approxPolyDP;
import static org.bytedeco.opencv.global.opencv_imgproc.arcLength;
import static org.bytedeco.opencv.global.opencv_imgproc.boundingRect;
import static org.bytedeco.opencv.global.opencv_imgproc.contourArea;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.dilate;
import static org.bytedeco.opencv.global.opencv_imgproc.findContours;
import static org.bytedeco.opencv.global.opencv_imgproc.getPerspectiveTransform;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

/**
 * ArUco + homography dimension engine (see {@link DimensionEngine}).
 *
 * <p><b>Capture protocol (v1):</b> the DA lays the printed ArUco marker <i>flat on the face being
 * measured</i> and shoots roughly perpendicular. Because the marker is coplanar with that face, the
 * marker's four corners (known real size) give an exact perspective-correcting homography from image
 * pixels to centimetres on that plane — so we can measure the parcel outline in that plane without
 * any 3D reconstruction. TOP photo → length & width; SIDE photo → height.</p>
 *
 * <p>Every path is guarded: a missing native lib, unreadable image, absent marker, or timeout all
 * return a non-OK {@link MeasurementResult} instead of throwing. All OpenCV work runs on a bounded
 * worker pool with a hard timeout so a slow/hung call can't tie up request threads.</p>
 */
@Component
public class OpenCvArucoDimensionEngine implements DimensionEngine {

    private static final Logger log = LoggerFactory.getLogger(OpenCvArucoDimensionEngine.class);

    /** Attempt to load the native lib once at class-load; never fatal. */
    private static final boolean NATIVE_OK = tryLoadNative();

    private static boolean tryLoadNative() {
        try {
            // Force the javacpp opencv_core preset to load its native library now.
            org.bytedeco.opencv.global.opencv_core.getBuildInformation();
            return true;
        } catch (Throwable t) { // NOSONAR — native load can throw Error (UnsatisfiedLinkError)
            LoggerFactory.getLogger(OpenCvArucoDimensionEngine.class)
                    .warn("OpenCV native library unavailable — dimension engine disabled: {}", t.toString());
            return false;
        }
    }

    private final VisionProperties props;
    private final ExecutorService pool;

    public OpenCvArucoDimensionEngine(VisionProperties props) {
        this.props = props;
        this.pool = Executors.newFixedThreadPool(2, daemon("vision-cv"));
        if (NATIVE_OK) {
            log.info("OpenCV dimension engine ready (markerSizeCm={}, dict={})",
                    props.getMarkerSizeCm(), props.getDictionaryId());
        }
    }

    @Override
    public boolean isAvailable() {
        return NATIVE_OK;
    }

    @Override
    public MeasurementResult measure(List<Capture> captures) {
        if (!NATIVE_OK) {
            return MeasurementResult.failed(MeasurementResult.Status.ENGINE_UNAVAILABLE, "native lib not loaded");
        }
        if (captures == null || captures.isEmpty()) {
            return MeasurementResult.failed(MeasurementResult.Status.BAD_INPUT, "no captures");
        }
        Future<MeasurementResult> f = pool.submit((Callable<MeasurementResult>) () -> measureInternal(captures));
        try {
            return f.get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return MeasurementResult.failed(MeasurementResult.Status.TIMEOUT, "cv timed out");
        } catch (Throwable t) {
            log.warn("Dimension measurement failed", t);
            return MeasurementResult.failed(MeasurementResult.Status.ERROR, String.valueOf(t.getMessage()));
        }
    }

    // ── core ─────────────────────────────────────────────────────────────────

    private MeasurementResult measureInternal(List<Capture> captures) {
        FaceMeasurement top = null;
        FaceMeasurement side = null;
        for (Capture c : captures) {
            FaceMeasurement fm = measureFace(c.imageBytes());
            if (fm == null) continue;
            if (c.view() == DimensionEngine.View.SIDE && side == null) side = fm;
            else if (top == null) top = fm;   // TOP or UNKNOWN treated as the primary face
            else if (side == null) side = fm;
        }
        if (top == null) {
            return MeasurementResult.failed(MeasurementResult.Status.NO_MARKER,
                    "no marker/face resolved in any capture");
        }

        double length = Math.max(top.a, top.b);
        double width = Math.min(top.a, top.b);
        double confidence = top.confidence;
        Double height = null;

        if (side != null) {
            // The side face shares one horizontal edge (≈ length or width) with the top; the other
            // side is the height. Pick the side value closest to a known horizontal edge as the
            // shared edge, and take the remaining one as height.
            double[] s = {side.a, side.b};
            int sharedIdx = closenessToKnown(s[0], length, width) <= closenessToKnown(s[1], length, width) ? 0 : 1;
            height = s[1 - sharedIdx];
            confidence = Math.min(confidence, side.confidence);
        }

        if (confidence < props.getMinConfidence()) {
            return MeasurementResult.failed(MeasurementResult.Status.LOW_CONFIDENCE,
                    String.format("confidence %.2f below threshold", confidence));
        }
        return MeasurementResult.ok(round1(length), round1(width),
                height != null ? round1(height) : null, confidence,
                height != null ? "top+side" : "top-only (no height)");
    }

    /** Detect the marker + the largest rectangle on its plane; return the two in-plane cm dims. */
    private FaceMeasurement measureFace(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        Mat encoded = new Mat(1, imageBytes.length, CV_8UC1, new BytePointer(imageBytes));
        Mat img = imdecode(encoded, IMREAD_COLOR);
        if (img == null || img.empty()) return null;
        try {
            img = downscale(img);
            Mat markerImgPts = detectFirstMarker(img);   // 4x1 CV_32FC2 (px), or null
            if (markerImgPts == null) return null;

            Mat markerCmPts = markerSquareCm();          // 4x1 CV_32FC2 (cm)
            Mat h = getPerspectiveTransform(markerImgPts, markerCmPts);

            Rect markerBox = boundingRect(markerImgPts);
            Mat parcelImgPts = detectLargestQuad(img, markerBox); // 4x1 CV_32FC2 (px), or null
            if (parcelImgPts == null) return null;

            Mat parcelCmPts = new Mat();
            perspectiveTransform(parcelImgPts, parcelCmPts, h);

            double[] sides = quadSideLengths(parcelCmPts);   // [s01, s12, s23, s30]
            double dimA = (sides[0] + sides[2]) / 2.0;       // one pair of opposite sides
            double dimB = (sides[1] + sides[3]) / 2.0;       // the other pair
            double rectness = rectangularity(sides);
            return new FaceMeasurement(dimA, dimB, rectness);
        } catch (Throwable t) {
            log.debug("measureFace failed: {}", t.toString());
            return null;
        }
    }

    private Mat downscale(Mat img) {
        int longest = Math.max(img.rows(), img.cols());
        int max = props.getMaxImageEdgePx();
        if (longest <= max) return img;
        double f = (double) max / longest;
        Mat out = new Mat();
        resize(img, out, new Size((int) Math.round(img.cols() * f), (int) Math.round(img.rows() * f)));
        return out;
    }

    /** Returns the first detected marker's 4 corners as a 4x1 CV_32FC2 Mat (px), or null. */
    private Mat detectFirstMarker(Mat img) {
        Dictionary dict = org.bytedeco.opencv.global.opencv_objdetect
                .getPredefinedDictionary(props.getDictionaryId());
        ArucoDetector detector = new ArucoDetector(dict, new DetectorParameters(), new RefineParameters());
        MatVector corners = new MatVector();
        Mat ids = new Mat();
        detector.detectMarkers(img, corners, ids);
        if (corners.size() == 0) return null;
        Mat first = corners.get(0);   // 1x4 CV_32FC2, order TL,TR,BR,BL
        FloatIndexer in = first.createIndexer();
        Mat out = new Mat(4, 1, CV_32FC2);
        FloatIndexer o = out.createIndexer();
        for (int k = 0; k < 4; k++) {
            o.put(k, 0, 0, in.get(0, k, 0));
            o.put(k, 0, 1, in.get(0, k, 1));
        }
        o.release();
        in.release();
        return out;
    }

    /** Marker corners in cm-plane coordinates, order TL,TR,BR,BL, side = markerSizeCm. */
    private Mat markerSquareCm() {
        float s = (float) props.getMarkerSizeCm();
        Mat m = new Mat(4, 1, CV_32FC2);
        FloatIndexer o = m.createIndexer();
        float[][] pts = {{0, 0}, {s, 0}, {s, s}, {0, s}};
        for (int k = 0; k < 4; k++) {
            o.put(k, 0, 0, pts[k][0]);
            o.put(k, 0, 1, pts[k][1]);
        }
        o.release();
        return m;
    }

    /** Largest 4-corner contour on the plane, excluding the marker's own box; 4x1 CV_32FC2 px or null. */
    private Mat detectLargestQuad(Mat img, Rect markerBox) {
        Mat gray = new Mat();
        cvtColor(img, gray, COLOR_BGR2GRAY);
        GaussianBlur(gray, gray, new Size(5, 5), 0);
        Mat edges = new Mat();
        Canny(gray, edges, 50, 150);
        dilate(edges, edges, new Mat());   // close small gaps in the box outline

        MatVector contours = new MatVector();
        findContours(edges, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

        double markerArea = (double) markerBox.width() * markerBox.height();
        double bestArea = -1;
        Mat best = null;
        for (long i = 0; i < contours.size(); i++) {
            Mat c = contours.get(i);
            double area = contourArea(c);
            if (area < Math.max(markerArea * 1.2, 500)) continue;   // must be bigger than the marker
            Mat approx = new Mat();
            double peri = arcLength(c, true);
            approxPolyDP(c, approx, 0.02 * peri, true);
            if (approx.rows() != 4) continue;
            Rect box = boundingRect(approx);
            if (contains(markerBox, center(box)) && box.width() <= markerBox.width() * 1.3) continue; // it's the marker
            if (area > bestArea) {
                bestArea = area;
                best = approx;
            }
        }
        if (best == null) return null;
        // approxPolyDP gives Nx1 CV_32SC2 (int); convert to 4x1 CV_32FC2.
        Mat out = new Mat(4, 1, CV_32FC2);
        FloatIndexer o = out.createIndexer();
        IntIndexer in = best.createIndexer();
        for (int k = 0; k < 4; k++) {
            o.put(k, 0, 0, (float) in.get(k, 0, 0));
            o.put(k, 0, 1, (float) in.get(k, 0, 1));
        }
        o.release();
        in.release();
        return out;
    }

    private static double[] quadSideLengths(Mat quadCm) {
        FloatIndexer p = quadCm.createIndexer();
        float[] xs = new float[4];
        float[] ys = new float[4];
        for (int k = 0; k < 4; k++) {
            xs[k] = p.get(k, 0, 0);
            ys[k] = p.get(k, 0, 1);
        }
        p.release();
        double[] s = new double[4];
        for (int k = 0; k < 4; k++) {
            int n = (k + 1) % 4;
            s[k] = Math.hypot(xs[n] - xs[k], ys[n] - ys[k]);
        }
        return s;
    }

    /** 1.0 = perfect rectangle (opposite sides equal), →0 as opposite sides diverge. */
    private static double rectangularity(double[] s) {
        double p1 = ratio(s[0], s[2]);
        double p2 = ratio(s[1], s[3]);
        return p1 * p2;
    }

    private static double ratio(double a, double b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        return hi <= 0 ? 0 : lo / hi;
    }

    private static double closenessToKnown(double v, double length, double width) {
        return Math.min(Math.abs(v - length), Math.abs(v - width));
    }

    private static boolean contains(Rect r, Point pt) {
        return pt.x() >= r.x() && pt.x() <= r.x() + r.width()
                && pt.y() >= r.y() && pt.y() <= r.y() + r.height();
    }

    private static Point center(Rect r) {
        return new Point(r.x() + r.width() / 2, r.y() + r.height() / 2);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** One face's two in-plane dimensions (cm) + a 0..1 quality score. */
    private record FaceMeasurement(double a, double b, double confidence) {}
}
