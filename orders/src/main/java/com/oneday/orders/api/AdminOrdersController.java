package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.dto.ShipmentAgeingStats;
import com.oneday.orders.dto.ShipmentPageResponse;
import com.oneday.orders.dto.ShipmentSummaryResponse;
import com.oneday.orders.dto.ShipmentSummaryStats;
import com.oneday.orders.service.AdminOrderQueryService;
import com.oneday.orders.service.AdminOrderSummaryService;
import com.oneday.orders.service.CancellationService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Orders-database access for ops tooling. The counterpart to the customer booking endpoints — these
 * roles cannot book (see {@link Authz#requireCustomerRole}) but can read, and now also cancel:
 * <ul>
 *   <li><b>ADMIN</b> — reads every city; cancels any shipment, any lane.</li>
 *   <li><b>STATION_MANAGER</b> — reads every shipment whose origin OR destination is their own city
 *       (the custody model's "X, Y can read" rule; each row carries {@code custody_city} +
 *       {@code can_act} so the manager sees which parcels they currently own). Cancel is narrower
 *       than read: only a shipment whose <em>current</em> custodian is their city.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/shipments")
class AdminOrdersController {

    private static final String STATION_MANAGER = "STATION_MANAGER";

    private final AdminOrderQueryService adminOrderQueryService;
    private final AdminOrderSummaryService adminOrderSummaryService;
    private final CancellationService cancellationService;

    AdminOrdersController(AdminOrderQueryService adminOrderQueryService,
                          AdminOrderSummaryService adminOrderSummaryService,
                          CancellationService cancellationService) {
        this.adminOrderQueryService = adminOrderQueryService;
        this.adminOrderSummaryService = adminOrderSummaryService;
        this.cancellationService = cancellationService;
    }

    @GetMapping
    public ShipmentPageResponse listShipments(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        // ADMIN (always) + STATION_MANAGER; everyone else → 403.
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderQueryService.listShipments(state, cityScope(principal), page, size);
    }

    /**
     * Aggregate parcel counts for the ops monitoring dashboard — per ops bucket and per raw state.
     * Same visibility as {@link #listShipments}: ADMIN counts every city (null scope), a
     * STATION_MANAGER counts only shipments touching their own city.
     */
    @GetMapping("/summary")
    public ShipmentSummaryStats summary(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderSummaryService.summary(cityScope(principal));
    }

    /**
     * Ageing rollup — live shipments grouped by ops stage and how long since their last scan, so ops
     * catch parcels stuck before a flight/cron cutoff. Same visibility as {@link #summary}.
     */
    @GetMapping("/ageing")
    public ShipmentAgeingStats ageing(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderSummaryService.ageing(cityScope(principal));
    }

    /**
     * Export every matching shipment as CSV (ops "export all"), same {@code state} filter + city scope as
     * {@link #listShipments} but without the 200-row page cap. Streams a downloadable attachment.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<Resource> exportShipments(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "state", required = false) String state) {
        Authz.requireRole(principal, STATION_MANAGER);
        List<ShipmentSummaryResponse> rows = adminOrderQueryService.exportShipments(state, cityScope(principal));

        byte[] csv = toCsv(rows).getBytes(StandardCharsets.UTF_8);
        String filename = "shipments-" + LocalDate.now() + (state == null || state.isBlank() ? "" : "-" + state) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new ByteArrayResource(csv));
    }

    private static final String[] CSV_HEADERS = {
            "shipment_ref", "customer_type", "delivery_type", "state", "pickup_type", "payment_mode",
            "origin_city", "origin_pincode", "dest_city", "dest_pincode", "sender_name", "receiver_name",
            "chargeable_weight_grams", "total_price_paise", "created_at", "cancelled_at", "custody_city"
    };

    static String toCsv(List<ShipmentSummaryResponse> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", CSV_HEADERS)).append("\r\n");
        for (ShipmentSummaryResponse r : rows) {
            sb.append(csv(r.shipmentRef())).append(',')
              .append(csv(r.customerType())).append(',')
              .append(csv(r.deliveryType())).append(',')
              .append(csv(r.state())).append(',')
              .append(csv(r.pickupType())).append(',')
              .append(csv(r.paymentMode())).append(',')
              .append(csv(r.originCity())).append(',')
              .append(csv(r.originPincode())).append(',')
              .append(csv(r.destCity())).append(',')
              .append(csv(r.destPincode())).append(',')
              .append(csv(r.senderName())).append(',')
              .append(csv(r.receiverName())).append(',')
              .append(csv(r.chargeableWeightGrams())).append(',')
              .append(csv(r.totalPricePaise())).append(',')
              .append(csv(r.createdAt())).append(',')
              .append(csv(r.cancelledAt())).append(',')
              .append(csv(r.custodyCity())).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * RFC-4180 cell + CSV-injection guard. A value beginning with {@code = + - @} (or a tab/CR that
     * spreadsheets treat the same) is prefixed with an apostrophe so a sender/receiver name can't be
     * evaluated as a formula when the export is opened in a spreadsheet; then it's quoted + quote-doubled
     * if it contains a comma/quote/newline.
     */
    static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** Null for ADMIN (all cities); the station manager's own city otherwise (403 if unassigned). */
    private static String cityScope(AuthUserDetails principal) {
        if (!STATION_MANAGER.equals(principal.getUser().getRole().getName())) {
            return null;
        }
        String cityScope = principal.getUser().getCityId();
        if (cityScope == null || cityScope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Station manager has no city assigned");
        }
        return cityScope;
    }

    /**
     * Admin row-action cancel from the orders database. ADMIN cancels any shipment, any lane, no
     * ownership check. STATION_MANAGER may also cancel, but only a shipment currently in their own
     * city's custody ({@link com.oneday.orders.service.ShipmentCustody}) — a ref outside that scope
     * 404s rather than 403ing, matching the b2b/b2c lane guard elsewhere in this module. The
     * cancellation cutoff still applies either way (→ 409 if past it).
     */
    @DeleteMapping("/{ref}")
    public CancellationResponse cancelShipment(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref,
            @RequestParam(value = "reason", required = false) String reason) {
        Authz.requireRole(principal, STATION_MANAGER);
        String userId = Authz.requireUserId(principal);

        if (STATION_MANAGER.equals(principal.getUser().getRole().getName())) {
            String cityScope = principal.getUser().getCityId();
            if (cityScope == null || cityScope.isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Station manager has no city assigned");
            }
            return cancellationService.cancelAsStationManager(ref, reason, userId, cityScope);
        }
        return cancellationService.cancelAsAdmin(ref, reason, userId);
    }
}
