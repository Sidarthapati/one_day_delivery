package com.oneday.orders.dto;

import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.orders.domain.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class BookingRequest {

    // ── Sender ────────────────────────────────────────────────────────────────

    @NotBlank @Size(max = 100) private String senderName;
    @NotBlank @Size(max = 15) @Pattern(regexp = "\\+91[0-9]{10}", message = "must be E.164 format (+91XXXXXXXXXX)") private String senderPhone;
    @Size(max = 254) @Email    private String senderEmail;

    @NotNull @Valid             private Address originAddress;
    @NotBlank @Size(max = 10)  private String originCity;
    @NotBlank @Size(max = 10) @Pattern(regexp = "\\d{6}", message = "must be a 6-digit pincode") private String originPincode;

    // ── Receiver ──────────────────────────────────────────────────────────────

    @NotBlank @Size(max = 100) private String receiverName;
    @NotBlank @Size(max = 15) @Pattern(regexp = "\\+91[0-9]{10}", message = "must be E.164 format (+91XXXXXXXXXX)") private String receiverPhone;
    @Size(max = 254) @Email    private String receiverEmail;

    @NotNull @Valid             private Address destAddress;
    @NotBlank @Size(max = 10)  private String destCity;
    @NotBlank @Size(max = 10) @Pattern(regexp = "\\d{6}", message = "must be a 6-digit pincode") private String destPincode;

    // ── Parcel dimensions ─────────────────────────────────────────────────────

    @Positive @Max(70_000) private int   weightGrams;
    @Positive @Max(150)    private short lengthCm;
    @Positive @Max(150)    private short widthCm;
    @Positive @Max(150)    private short heightCm;

    // ── Extras ────────────────────────────────────────────────────────────────

    @PositiveOrZero private Long declaredValuePaise;

    @NotNull private PickupType pickupType;
    @NotNull private DropType   dropType;

    // ── Payment ───────────────────────────────────────────────────────────────

    @NotNull private PaymentMode paymentMode;

    // Razorpay fields are required only for PREPAID; validated in BookingServiceImpl.
    @Size(max = 100) private String razorpayOrderId;
    @Size(max = 100) private String razorpayPaymentId;
    @Size(max = 500) private String razorpaySignature;

    // ── Getters / setters ─────────────────────────────────────────────────────

    public String getSenderName()       { return senderName; }
    public void setSenderName(String v) { this.senderName = v; }

    public String getSenderPhone()       { return senderPhone; }
    public void setSenderPhone(String v) { this.senderPhone = v; }

    public String getSenderEmail()       { return senderEmail; }
    public void setSenderEmail(String v) { this.senderEmail = v; }

    public Address getOriginAddress()       { return originAddress; }
    public void setOriginAddress(Address v) { this.originAddress = v; }

    public String getOriginCity()       { return originCity; }
    public void setOriginCity(String v) { this.originCity = v; }

    public String getOriginPincode()       { return originPincode; }
    public void setOriginPincode(String v) { this.originPincode = v; }

    public String getReceiverName()       { return receiverName; }
    public void setReceiverName(String v) { this.receiverName = v; }

    public String getReceiverPhone()       { return receiverPhone; }
    public void setReceiverPhone(String v) { this.receiverPhone = v; }

    public String getReceiverEmail()       { return receiverEmail; }
    public void setReceiverEmail(String v) { this.receiverEmail = v; }

    public Address getDestAddress()       { return destAddress; }
    public void setDestAddress(Address v) { this.destAddress = v; }

    public String getDestCity()       { return destCity; }
    public void setDestCity(String v) { this.destCity = v; }

    public String getDestPincode()       { return destPincode; }
    public void setDestPincode(String v) { this.destPincode = v; }

    public int getWeightGrams()       { return weightGrams; }
    public void setWeightGrams(int v) { this.weightGrams = v; }

    public short getLengthCm()        { return lengthCm; }
    public void setLengthCm(short v)  { this.lengthCm = v; }

    public short getWidthCm()         { return widthCm; }
    public void setWidthCm(short v)   { this.widthCm = v; }

    public short getHeightCm()        { return heightCm; }
    public void setHeightCm(short v)  { this.heightCm = v; }

    public Long getDeclaredValuePaise()       { return declaredValuePaise; }
    public void setDeclaredValuePaise(Long v) { this.declaredValuePaise = v; }

    public PickupType getPickupType()       { return pickupType; }
    public void setPickupType(PickupType v) { this.pickupType = v; }

    public DropType getDropType()       { return dropType; }
    public void setDropType(DropType v) { this.dropType = v; }

    public PaymentMode getPaymentMode()           { return paymentMode; }
    public void setPaymentMode(PaymentMode v)     { this.paymentMode = v; }

    public String getRazorpayOrderId()       { return razorpayOrderId; }
    public void setRazorpayOrderId(String v) { this.razorpayOrderId = v; }

    public String getRazorpayPaymentId()       { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v) { this.razorpayPaymentId = v; }

    public String getRazorpaySignature()       { return razorpaySignature; }
    public void setRazorpaySignature(String v) { this.razorpaySignature = v; }
}
