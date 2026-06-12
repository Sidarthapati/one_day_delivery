package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.orders.domain.Address;
import com.oneday.orders.dto.AddCartItemRequest;
import com.oneday.orders.dto.BulkPickup;
import com.oneday.orders.dto.BulkUploadResponse;
import com.oneday.orders.dto.BulkUploadResponse.FieldError;
import com.oneday.orders.dto.BulkUploadResponse.RowFailure;
import com.oneday.orders.service.BulkUploadService;
import com.oneday.orders.service.CartService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class BulkUploadServiceImpl implements BulkUploadService {

    /**
     * Destination columns only — the pickup + sender come from the booking form, and
     * pickup/drop type are set internally. Coordinates are derived from the pincode.
     */
    private static final List<String> HEADERS = List.of(
            "receiver_name", "receiver_phone", "receiver_email",
            "dest_line1", "dest_line2", "dest_city", "dest_pincode", "dest_state",
            "weight_grams", "length_cm", "width_cm", "height_cm", "declared_value_inr");

    private static final String[] EXAMPLE = {
            "Priya Sharma", "+919000000002", "priya@example.com",
            "1 MG Road", "", "Bengaluru", "560001", "Karnataka",
            "1000", "20", "15", "10", "500"};

    private static final int MAX_ROWS = 500;

    // Pincode prefix → demo city code + that city's grid centroid (lat, lon). A destination's
    // serviceability resolves through the real grid at the centroid, so the sheet needs no coords.
    private static final Map<String, String> DEMO_BY_PREFIX =
            Map.of("110", "DEL", "560", "BLR", "400", "BOM", "600", "MAA", "500", "HYD");
    private static final Map<String, double[]> CENTROID = Map.of(
            "DEL", new double[]{28.6139, 77.2090},
            "BLR", new double[]{12.9716, 77.5946},
            "BOM", new double[]{19.0760, 72.8777},
            "MAA", new double[]{13.0827, 80.2707},
            "HYD", new double[]{17.3850, 78.4867});

    private final CartService cartService;
    private final Validator validator;

    BulkUploadServiceImpl(CartService cartService, Validator validator) {
        this.cartService = cartService;
        this.validator = validator;
    }

    @Override
    public List<String> headers() {
        return HEADERS;
    }

    @Override
    public byte[] generateTemplate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(out, "OneDayDelivery", "1.0")) {
            Worksheet ws = wb.newWorksheet("Destinations");
            for (int c = 0; c < HEADERS.size(); c++) {
                ws.value(0, c, HEADERS.get(c));
                ws.style(0, c).bold().set();
                ws.value(1, c, EXAMPLE[c]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate template", e);
        }
        return out.toByteArray();
    }

    @Override
    public BulkUploadResponse process(UUID userId, BulkPickup pickup, InputStream xlsx) {
        if (pickup == null || pickup.originAddress() == null
                || isBlank(pickup.originCity()) || isBlank(pickup.originPincode())) {
            throw new BadTemplateException("Set the pickup location on the map before uploading destinations.");
        }

        List<Row> rows;
        try (ReadableWorkbook wb = new ReadableWorkbook(xlsx)) {
            Sheet sheet = wb.getFirstSheet();
            if (sheet == null) throw new BadTemplateException("Workbook has no sheet");
            rows = sheet.read();
        } catch (BadTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw new BadTemplateException("Could not read the .xlsx file: " + e.getMessage());
        }

        if (rows.isEmpty()) throw new BadTemplateException("The sheet is empty");
        validateHeader(rows.get(0));

        List<Row> dataRows = rows.subList(1, rows.size());
        if (dataRows.size() > MAX_ROWS) {
            throw new BadTemplateException("Too many rows: " + dataRows.size() + " (max " + MAX_ROWS + ")");
        }

        // ── Pass 1: parse + validate every row in memory (no DB) ───────────────────
        List<RowFailure> failures = new java.util.ArrayList<>();
        List<Integer> validRowNums = new java.util.ArrayList<>();
        List<AddCartItemRequest> validReqs = new java.util.ArrayList<>();
        for (Row row : dataRows) {
            if (isBlankRow(row)) continue;
            int rowNum = row.getRowNum();
            List<FieldError> errors = new java.util.ArrayList<>();
            AddCartItemRequest req = mapRow(pickup, row, errors);

            if (errors.isEmpty()) {
                for (ConstraintViolation<AddCartItemRequest> v : validator.validate(req)) {
                    errors.add(new FieldError(v.getPropertyPath().toString(), v.getMessage()));
                }
            }
            if (errors.isEmpty()) {
                validRowNums.add(rowNum);
                validReqs.add(req);
            } else {
                failures.add(new RowFailure(rowNum, errors));
            }
        }

        // ── Pass 2: price + insert all valid rows in one transaction/batch ─────────
        int added = 0;
        List<CartService.BulkItemResult> results = cartService.addItems(userId, validReqs);
        for (int i = 0; i < results.size(); i++) {
            CartService.BulkItemResult r = results.get(i);
            if (r.added()) {
                added++;
            } else {
                failures.add(new RowFailure(validRowNums.get(i),
                        List.of(new FieldError("route", r.failureReason()))));
            }
        }
        failures.sort(java.util.Comparator.comparingInt(RowFailure::row));
        return new BulkUploadResponse(added, failures.size(), failures);
    }

    // ── Parsing / mapping ─────────────────────────────────────────────────────

    private void validateHeader(Row header) {
        for (int c = 0; c < HEADERS.size(); c++) {
            String actual = cell(header, c).toLowerCase();
            if (!HEADERS.get(c).equals(actual)) {
                throw new BadTemplateException(
                        "Header mismatch at column " + (c + 1) + ": expected '" + HEADERS.get(c)
                                + "' but found '" + actual + "'. Download the template for the correct format.");
            }
        }
    }

    private AddCartItemRequest mapRow(BulkPickup pickup, Row row, List<FieldError> errors) {
        AddCartItemRequest r = new AddCartItemRequest();

        // Shared pickup (same for every destination row).
        r.setSenderName(pickup.senderName());
        r.setSenderPhone(pickup.senderPhone());
        r.setSenderEmail(pickup.senderEmail());
        r.setOriginAddress(pickup.originAddress());
        r.setOriginCity(pickup.originCity());
        r.setOriginPincode(pickup.originPincode());

        // Destination from the row.
        r.setReceiverName(cellOrNull(row, 0));
        r.setReceiverPhone(cellOrNull(row, 1));
        r.setReceiverEmail(cellOrNull(row, 2));

        String destPincode = cellOrNull(row, 6);
        Address dest = new Address();
        dest.setLine1(cellOrNull(row, 3));
        dest.setLine2(cellOrNull(row, 4));
        dest.setCity(cellOrNull(row, 5));
        dest.setPincode(destPincode);
        dest.setState(cellOrNull(row, 7));

        // Derive the city (code + centroid) from the pincode prefix so serviceability resolves
        // through the real grid without coordinates in the sheet.
        String demoCity = null;
        if (destPincode != null && destPincode.length() >= 3) {
            demoCity = DEMO_BY_PREFIX.get(destPincode.substring(0, 3));
        }
        if (demoCity == null) {
            errors.add(new FieldError("dest_pincode", "not in a serviceable city (Delhi/Mumbai/Bengaluru/Chennai/Hyderabad)"));
        } else {
            double[] c = CENTROID.get(demoCity);
            dest.setLatitude(c[0]);
            dest.setLongitude(c[1]);
        }
        r.setDestAddress(dest);
        r.setDestCity(demoCity);            // city code for pricing
        r.setDestPincode(destPincode);

        r.setWeightGrams(intCell(row, 8, "weight_grams", errors));
        r.setLengthCm((short) intCell(row, 9, "length_cm", errors));
        r.setWidthCm((short) intCell(row, 10, "width_cm", errors));
        r.setHeightCm((short) intCell(row, 11, "height_cm", errors));
        Long rupees = longCell(row, 12, "declared_value_inr", errors);
        r.setDeclaredValuePaise(rupees == null ? null : rupees * 100);

        // Set internally — not part of the sheet.
        r.setPickupType(PickupType.DA_PICKUP);
        r.setDropType(DropType.DA_DELIVERY);
        return r;
    }

    // ── Cell helpers ──────────────────────────────────────────────────────────

    private static String cell(Row row, int idx) {
        if (idx >= row.getCellCount()) return "";
        // getCellAsString() throws on numeric cells, so read the raw value instead — this handles
        // strings, numbers (BigDecimal), booleans, etc. uniformly for both typed and text cells.
        org.dhatim.fastexcel.reader.Cell c = row.getCell(idx);
        if (c == null) return "";
        Object v = c.getValue();
        if (v == null) return "";
        if (v instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();   // 1000.0 → "1000"
        }
        return v.toString().trim();
    }

    private static String cellOrNull(Row row, int idx) {
        String v = cell(row, idx);
        return v.isEmpty() ? null : v;
    }

    private static int intCell(Row row, int idx, String field, List<FieldError> errors) {
        String v = cell(row, idx);
        if (v.isEmpty()) { errors.add(new FieldError(field, "is required")); return 0; }
        try { return (int) Math.round(Double.parseDouble(v)); }
        catch (NumberFormatException e) { errors.add(new FieldError(field, "must be a number")); return 0; }
    }

    private static Long longCell(Row row, int idx, String field, List<FieldError> errors) {
        String v = cell(row, idx);
        if (v.isEmpty()) return null;
        try { return (long) Math.round(Double.parseDouble(v)); }
        catch (NumberFormatException e) { errors.add(new FieldError(field, "must be a number")); return null; }
    }

    private static boolean isBlankRow(Row row) {
        for (int c = 0; c < HEADERS.size() && c < row.getCellCount(); c++) {
            if (!cell(row, c).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
