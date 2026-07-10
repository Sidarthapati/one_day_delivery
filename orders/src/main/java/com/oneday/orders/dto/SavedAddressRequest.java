package com.oneday.orders.dto;

import com.oneday.orders.domain.enums.AddressLabel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a saved address. Label is required; the friendly name
 * ({@code saveAs}) and contact/instruction fields are optional. Mirrors the embedded
 * {@link com.oneday.orders.domain.Address} constraints for the address portion.
 */
public class SavedAddressRequest {

    @NotNull private AddressLabel label;

    @Size(max = 100) private String saveAs;

    @Size(max = 100) private String contactName;
    @Size(max = 15) @Pattern(regexp = "\\+91[0-9]{10}", message = "must be E.164 format (+91XXXXXXXXXX)")
    private String contactPhone;

    @Size(max = 200) private String houseFloor;
    @Size(max = 200) private String buildingStreet;
    @Size(max = 300) private String areaLocality;

    @NotBlank @Size(max = 200) private String line1;
    @Size(max = 200) private String line2;
    @NotBlank @Size(max = 100) private String city;
    @NotBlank @Size(max = 10) @Pattern(regexp = "\\d{6}", message = "must be a 6-digit pincode")
    private String pincode;
    @NotBlank @Size(max = 100) private String state;
    @Size(max = 200) private String landmark;

    @DecimalMin("6.0") @DecimalMax("38.0")  private Double latitude;
    @DecimalMin("68.0") @DecimalMax("98.0") private Double longitude;

    @Size(max = 500) private String deliveryInstructions;

    public AddressLabel getLabel() { return label; }
    public void setLabel(AddressLabel v) { this.label = v; }
    public String getSaveAs() { return saveAs; }
    public void setSaveAs(String v) { this.saveAs = v; }
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { this.contactPhone = v; }
    public String getHouseFloor() { return houseFloor; }
    public void setHouseFloor(String v) { this.houseFloor = v; }
    public String getBuildingStreet() { return buildingStreet; }
    public void setBuildingStreet(String v) { this.buildingStreet = v; }
    public String getAreaLocality() { return areaLocality; }
    public void setAreaLocality(String v) { this.areaLocality = v; }
    public String getLine1() { return line1; }
    public void setLine1(String v) { this.line1 = v; }
    public String getLine2() { return line2; }
    public void setLine2(String v) { this.line2 = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getPincode() { return pincode; }
    public void setPincode(String v) { this.pincode = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getLandmark() { return landmark; }
    public void setLandmark(String v) { this.landmark = v; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double v) { this.latitude = v; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double v) { this.longitude = v; }
    public String getDeliveryInstructions() { return deliveryInstructions; }
    public void setDeliveryInstructions(String v) { this.deliveryInstructions = v; }
}
