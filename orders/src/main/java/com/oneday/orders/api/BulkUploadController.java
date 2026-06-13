package com.oneday.orders.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.BulkPickup;
import com.oneday.orders.dto.BulkUploadResponse;
import com.oneday.orders.service.BulkUploadService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Excel bulk upload for the cart: one shared pickup → many destination rows. B2B + B2C only
 * (C2C is excluded — they book one-off). Download the destinations template, fill rows, and
 * upload along with the pickup chosen on the booking map; valid rows are added to the cart.
 */
@RestController
@RequestMapping("/api/v1/bulk")
class BulkUploadController {

    // Bulk is for business-style senders (B2C/B2B); C2C and ADMIN are not permitted.
    private static final String[] BULK_ROLES = {"B2C_CUSTOMER", "B2B_USER"};
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final BulkUploadService bulkUploadService;
    private final ObjectMapper objectMapper;

    BulkUploadController(BulkUploadService bulkUploadService, ObjectMapper objectMapper) {
        this.bulkUploadService = bulkUploadService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/template")
    public ResponseEntity<Resource> template(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireCustomerRole(principal, BULK_ROLES);
        byte[] bytes = bulkUploadService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"oneday-destinations-template.xlsx\"")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(new ByteArrayResource(bytes));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkUploadResponse upload(@AuthenticationPrincipal AuthUserDetails principal,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam("pickup") String pickupJson) throws IOException {
        Authz.requireCustomerRole(principal, BULK_ROLES);
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        BulkPickup pickup;
        try {
            pickup = objectMapper.readValue(pickupJson, BulkPickup.class);
        } catch (Exception e) {
            throw new BulkUploadService.BadTemplateException("Invalid pickup data: " + e.getMessage());
        }
        return bulkUploadService.process(userId, pickup, file.getInputStream());
    }
}
