package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.orders.domain.ParcelMeasurement;
import com.oneday.orders.repository.ParcelMeasurementRepository;
import com.oneday.vision.MeasurementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test of the first-mile "scan dimensions" flow: real HTTP → JWT filter → controller →
 * service → real Postgres, with object storage + the CV engine mocked (deterministic, no R2/OpenCV).
 * Also proves the V4_38 migration applies (Flyway runs on context start).
 */
class ParcelMeasurementE2eTest extends OrdersE2eSupport {

    // objectStoragePort + dimensionEngine are inherited @MockBeans from OrdersE2eSupport (declared
    // once there so every e2e context boots); this test stubs them for the measurement flow.
    @Autowired protected ParcelMeasurementRepository measurementRepository;

    @BeforeEach
    void storageAndEngineStubs() {
        lenient().when(objectStoragePort.isAvailable()).thenReturn(true);
        lenient().when(objectStoragePort.presignPut(anyString(), anyString(), any())).thenReturn("https://r2.example/put");
        lenient().when(objectStoragePort.presignGet(anyString(), any())).thenReturn("https://r2.example/get");
        lenient().when(objectStoragePort.exists(anyString())).thenReturn(true);
        lenient().when(objectStoragePort.size(anyString())).thenReturn(1_000L);
        lenient().when(objectStoragePort.getBytes(anyString())).thenReturn(new byte[]{1, 2, 3});
        lenient().when(dimensionEngine.isAvailable()).thenReturn(true);
    }

    private String daToken() {
        return tokenFor("DELIVERY_ASSOCIATE", randomUserId());
    }

    @Test
    void presignReturnsUploadSlots() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);   // declared 20x15x10

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement/upload-urls?count=2", ref)
                        .header("Authorization", "Bearer " + daToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].object_key").exists())
                .andExpect(jsonPath("$[0].upload_url").value("https://r2.example/put"));
    }

    @Test
    void overDeclaredMeasurementIsFlaggedAndPersisted() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);   // declared 20x15x10 (3000 cm³)

        // Measured 40x30x25 (30000 cm³) — grossly over-declared.
        when(dimensionEngine.measure(any())).thenReturn(MeasurementResult.ok(40.0, 30.0, 25.0, 0.9, "top+side"));

        String body = json.writeValueAsString(Map.of("captures", List.of(
                Map.of("object_key", key(ref, "top"), "view", "TOP"),
                Map.of("object_key", key(ref, "side"), "view", "SIDE"))));

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement", ref)
                        .header("Authorization", "Bearer " + daToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.over_declared").value(true))
                .andExpect(jsonPath("$.length_cm").value(40.0))
                .andExpect(jsonPath("$.volumetric_weight_grams").value(6000)); // 40*30*25/5000*1000

        UUID shipmentId = shipmentRepository.findByShipmentRef(ref).orElseThrow().getId();
        List<ParcelMeasurement> rows = measurementRepository.findByShipmentIdOrderByCreatedAtDesc(shipmentId);
        assertThat(rows).hasSize(1);
        ParcelMeasurement m = rows.get(0);
        assertThat(m.getStatus()).isEqualTo("OK");
        assertThat(m.isOverDeclared()).isTrue();
        assertThat(m.getSource().name()).isEqualTo("DA_PICKUP");
        assertThat(m.getEvidenceKeys()).hasSize(2);
        assertThat(m.getDeclaredLengthCm()).isEqualTo((short) 20);
    }

    @Test
    void withinToleranceNotFlagged() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);   // declared 20x15x10

        when(dimensionEngine.measure(any())).thenReturn(MeasurementResult.ok(20.5, 15.0, 10.0, 0.9, "top+side"));

        String body = json.writeValueAsString(Map.of("captures", List.of(
                Map.of("object_key", key(ref, "top"), "view", "TOP"))));

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement", ref)
                        .header("Authorization", "Bearer " + daToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.over_declared").value(false));
    }

    @Test
    void degradesWhenEngineUnavailableButStillRecords() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);
        when(dimensionEngine.isAvailable()).thenReturn(false);   // CV offline

        String body = json.writeValueAsString(Map.of("captures", List.of(
                Map.of("object_key", key(ref, "top"), "view", "TOP"))));

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement", ref)
                        .header("Authorization", "Bearer " + daToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())                       // never breaks pickup
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.status").value("ENGINE_UNAVAILABLE"))
                .andExpect(jsonPath("$.over_declared").value(false));

        UUID shipmentId = shipmentRepository.findByShipmentRef(ref).orElseThrow().getId();
        assertThat(measurementRepository.findByShipmentIdOrderByCreatedAtDesc(shipmentId)).hasSize(1);
    }

    @Test
    void rejectsForeignEvidenceKey() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);

        String body = json.writeValueAsString(Map.of("captures", List.of(
                Map.of("object_key", "pickup-measurements/2026/01/01/SOME-OTHER-REF/x.jpg", "view", "TOP"))));

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement", ref)
                        .header("Authorization", "Bearer " + daToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unassignedDaIsForbidden() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);
        // This DA is NOT the one assigned to the pickup.
        when(pickupAssignmentPort.isActivePickupDa(any(), any())).thenReturn(false);

        String body = json.writeValueAsString(Map.of("captures", List.of(
                Map.of("object_key", key(ref, "top"), "view", "TOP"))));

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement", ref)
                        .header("Authorization", "Bearer " + daToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());

        // ...and presign is gated the same way.
        mvc.perform(post("/internal/v1/shipments/{ref}/measurement/upload-urls?count=2", ref)
                        .header("Authorization", "Bearer " + daToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void oversizedEvidenceIsRejectedNotBuffered() throws Exception {
        String custToken = tokenFor("C2C_CUSTOMER", randomUserId());
        String ref = bookB2c(custToken, PaymentMode.PREPAID);
        when(objectStoragePort.size(anyString())).thenReturn(50L * 1024 * 1024);   // 50MB, over the 15MB cap

        String body = json.writeValueAsString(Map.of("captures", List.of(
                Map.of("object_key", key(ref, "top"), "view", "TOP"))));

        mvc.perform(post("/internal/v1/shipments/{ref}/measurement", ref)
                        .header("Authorization", "Bearer " + daToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));   // not measured; oversized object never buffered
        verify(objectStoragePort, never()).getBytes(anyString());
    }

    private static String key(String ref, String tag) {
        return "pickup-measurements/2026/01/01/" + ref + "/" + tag + "-" + UUID.randomUUID() + ".jpg";
    }
}
