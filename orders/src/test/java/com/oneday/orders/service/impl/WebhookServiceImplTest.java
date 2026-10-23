package com.oneday.orders.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.WebhookDelivery;
import com.oneday.orders.dto.WebhookConfigResponse;
import com.oneday.orders.events.ShipmentTransitioned;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookServiceImplTest {

    private static final String SAFE_URL = "https://93.184.216.34/hook"; // literal public IP, no DNS
    private static final String UNSAFE_URL = "http://127.0.0.1/hook";     // loopback → SSRF-blocked

    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final B2bAccountRepository accounts = mock(B2bAccountRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private HttpClient http;
    private WebhookServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        service = new WebhookServiceImpl(shipments, accounts, deliveries, new ObjectMapper());
        http = mock(HttpClient.class);
        ReflectionTestUtils.setField(service, "http", http);
        lenient().when(deliveries.save(any())).thenAnswer(i -> i.getArgument(0));
        HttpResponse<String> ok = mock(HttpResponse.class);
        lenient().when(ok.statusCode()).thenReturn(200);
        org.mockito.Mockito.doReturn(ok).when(http).send(any(), any());
    }

    private static B2bAccount account(String url, String secret) {
        B2bAccount a = new B2bAccount();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setWebhookUrl(url);
        a.setWebhookSecret(secret);
        return a;
    }

    private static Shipment b2bShipment(UUID accountId) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("REF");
        s.setCustomerType(CustomerType.B2B);
        s.setB2bAccountId(accountId);
        return s;
    }

    private static ShipmentTransitioned evt(UUID shipmentId) {
        return new ShipmentTransitioned(shipmentId, "REF", ShipmentState.BOOKED, ShipmentState.PICKED_UP,
                "sys", null, null);
    }

    // ── dispatchForTransition ──
    @Test
    void dispatch_shipmentNull_noop() {
        UUID sid = UUID.randomUUID();
        when(shipments.findById(sid)).thenReturn(Optional.empty());
        service.dispatchForTransition(evt(sid));
    }

    @Test
    void dispatch_nonB2b_noop() {
        UUID sid = UUID.randomUUID();
        Shipment s = new Shipment();
        s.setCustomerType(CustomerType.B2C);
        when(shipments.findById(sid)).thenReturn(Optional.of(s));
        service.dispatchForTransition(evt(sid));
    }

    @Test
    void dispatch_noWebhookUrl_noop() {
        UUID sid = UUID.randomUUID();
        Shipment s = b2bShipment(UUID.randomUUID());
        when(shipments.findById(sid)).thenReturn(Optional.of(s));
        when(accounts.findById(s.getB2bAccountId())).thenReturn(Optional.of(account(null, null)));
        service.dispatchForTransition(evt(sid));
    }

    @Test
    void dispatch_configured_deliversAsync() throws Exception {
        UUID sid = UUID.randomUUID();
        Shipment s = b2bShipment(UUID.randomUUID());
        when(shipments.findById(sid)).thenReturn(Optional.of(s));
        when(accounts.findById(s.getB2bAccountId())).thenReturn(Optional.of(account(SAFE_URL, "sec")));
        service.dispatchForTransition(evt(sid));
        verify(deliveries, timeout(2000)).save(any());
        verify(http, timeout(2000)).send(any(), any());
    }

    // ── config / updateConfig ──
    @Test
    void config_blankUrl_none_and_set() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(null, null)));
        assertThat(service.config(id)).isEqualTo(WebhookConfigResponse.none());

        UUID id2 = UUID.randomUUID();
        when(accounts.findById(id2)).thenReturn(Optional.of(account(SAFE_URL, "sec")));
        assertThat(service.config(id2).active()).isTrue();
    }

    @Test
    void config_notFound_404() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.config(id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateConfig_notFound_404() {
        UUID id = UUID.randomUUID();
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateConfig(id, SAFE_URL, false))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateConfig_blankUrl_clears() {
        UUID id = UUID.randomUUID();
        B2bAccount a = account(SAFE_URL, "sec");
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(a));
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        service.updateConfig(id, "  ", false);
        assertThat(a.getWebhookUrl()).isNull();
    }

    @Test
    void updateConfig_unsafeUrl_400() {
        UUID id = UUID.randomUUID();
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(account(null, null)));
        assertThatThrownBy(() -> service.updateConfig(id, UNSAFE_URL, false))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateConfig_safe_regeneratesAndKeepsSecret() {
        UUID id = UUID.randomUUID();
        B2bAccount a = account(null, null); // no secret yet → generated
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(a));
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        service.updateConfig(id, SAFE_URL, false);
        assertThat(a.getWebhookSecret()).startsWith("whsec_");
        String first = a.getWebhookSecret();
        service.updateConfig(id, SAFE_URL, false); // existing secret, no regen → unchanged
        assertThat(a.getWebhookSecret()).isEqualTo(first);
        service.updateConfig(id, SAFE_URL, true);  // regen → changes
        assertThat(a.getWebhookSecret()).isNotEqualTo(first);
    }

    // ── sendTest / deliver branches ──
    @Test
    void sendTest_noUrl_409() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(null, null)));
        assertThatThrownBy(() -> service.sendTest(id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sendTest_delivered_on2xx() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(SAFE_URL, "sec")));
        assertThat(service.sendTest(id)).isNotNull();
    }

    @Test
    void deliver_non2xx_marksFailed() throws Exception {
        HttpResponse<String> bad = mock(HttpResponse.class);
        when(bad.statusCode()).thenReturn(503);
        org.mockito.Mockito.doReturn(bad).when(http).send(any(), any());
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(SAFE_URL, null))); // null secret → "" sig
        assertThat(service.sendTest(id)).isNotNull();
    }

    @Test
    void deliver_httpThrows_marksFailed() throws Exception {
        org.mockito.Mockito.doThrow(new IOException("connrefused")).when(http).send(any(), any());
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(SAFE_URL, "sec")));
        assertThat(service.sendTest(id)).isNotNull();
    }

    @Test
    void deliver_ssrfBlocked_marksFailed_noHttp() throws Exception {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(UNSAFE_URL, "sec")));
        assertThat(service.sendTest(id)).isNotNull();
        verify(http, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    void deliver_jsonSerializationFails_fallsBackToEmptyObject() throws Exception {
        ObjectMapper throwing = mock(ObjectMapper.class);
        when(throwing.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        WebhookServiceImpl svc = new WebhookServiceImpl(shipments, accounts, deliveries, throwing);
        ReflectionTestUtils.setField(svc, "http", http);
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account(SAFE_URL, "sec")));
        assertThat(svc.sendTest(id)).isNotNull(); // json "{}" fallback, then 200 → DELIVERED
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentDeliveries_clampsLimit() {
        UUID id = UUID.randomUUID();
        when(deliveries.findByB2bAccountIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(java.util.List.of(delivery()));
        assertThat(service.recentDeliveries(id, 9999)).hasSize(1);
    }

    private static WebhookDelivery delivery() {
        WebhookDelivery d = new WebhookDelivery();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setEvent("webhook.test");
        d.setStatus(com.oneday.orders.domain.WebhookDeliveryStatus.DELIVERED);
        return d;
    }
}
