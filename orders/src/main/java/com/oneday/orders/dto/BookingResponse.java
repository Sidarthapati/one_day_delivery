package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.Map;

public class BookingResponse {

    private String shipmentRef;
    private ShipmentState state;               // machine-readable (BOOKED)
    private String stateLabel;                 // human-readable ("Order confirmed")
    private DeliveryType deliveryType;
    private PricingDetails pricing;
    private Instant etaPromised;               // null when ETA call fails
    private Integer slaCommitmentMinutes;      // null when ETA call fails
    private String trackingUrl;
    private String parcelId;                   // always null at booking time
    private String labelStatus;               // always "PENDING" at booking time
    private PaymentSummary payment;

    // ── Nested: pricing block ─────────────────────────────────────────────────

    public static class PricingDetails {
        private long quotedPricePaise;
        private long gstPaise;
        private long totalPricePaise;
        private String currency;
        private Map<String, Long> breakdown;
        private String rateCardVersion;

        public long getQuotedPricePaise()              { return quotedPricePaise; }
        public void setQuotedPricePaise(long v)        { this.quotedPricePaise = v; }

        public long getGstPaise()                      { return gstPaise; }
        public void setGstPaise(long v)                { this.gstPaise = v; }

        public long getTotalPricePaise()               { return totalPricePaise; }
        public void setTotalPricePaise(long v)         { this.totalPricePaise = v; }

        public String getCurrency()                    { return currency; }
        public void setCurrency(String v)              { this.currency = v; }

        public Map<String, Long> getBreakdown()        { return breakdown; }
        public void setBreakdown(Map<String, Long> v)  { this.breakdown = v; }

        public String getRateCardVersion()             { return rateCardVersion; }
        public void setRateCardVersion(String v)       { this.rateCardVersion = v; }
    }

    // ── Nested: payment summary block ─────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PaymentSummary {
        private PaymentMode mode;
        private String status;
        private String razorpayPaymentId;

        public PaymentMode getMode()                   { return mode; }
        public void setMode(PaymentMode v)             { this.mode = v; }

        public String getStatus()                      { return status; }
        public void setStatus(String v)                { this.status = v; }

        public String getRazorpayPaymentId()           { return razorpayPaymentId; }
        public void setRazorpayPaymentId(String v)     { this.razorpayPaymentId = v; }
    }

    // ── Top-level getters/setters ─────────────────────────────────────────────

    public String getShipmentRef()                     { return shipmentRef; }
    public void setShipmentRef(String v)               { this.shipmentRef = v; }

    public ShipmentState getState()                    { return state; }
    public void setState(ShipmentState v)              { this.state = v; }

    public String getStateLabel()                      { return stateLabel; }
    public void setStateLabel(String v)                { this.stateLabel = v; }

    public DeliveryType getDeliveryType()              { return deliveryType; }
    public void setDeliveryType(DeliveryType v)        { this.deliveryType = v; }

    public PricingDetails getPricing()                 { return pricing; }
    public void setPricing(PricingDetails v)           { this.pricing = v; }

    public Instant getEtaPromised()                    { return etaPromised; }
    public void setEtaPromised(Instant v)              { this.etaPromised = v; }

    public Integer getSlaCommitmentMinutes()           { return slaCommitmentMinutes; }
    public void setSlaCommitmentMinutes(Integer v)     { this.slaCommitmentMinutes = v; }

    public String getTrackingUrl()                     { return trackingUrl; }
    public void setTrackingUrl(String v)               { this.trackingUrl = v; }

    public String getParcelId()                        { return parcelId; }
    public void setParcelId(String v)                  { this.parcelId = v; }

    public String getLabelStatus()                     { return labelStatus; }
    public void setLabelStatus(String v)               { this.labelStatus = v; }

    public PaymentSummary getPayment()                 { return payment; }
    public void setPayment(PaymentSummary v)           { this.payment = v; }
}
