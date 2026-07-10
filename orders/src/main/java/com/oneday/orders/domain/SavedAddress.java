package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.orders.domain.enums.AddressLabel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A user's saved delivery/pickup address. Reused as either leg when booking or adding a
 * cart item. User-scoped: the API only ever reads/writes rows for the authenticated user.
 */
@Entity
@Table(name = "saved_address")
@Getter
@Setter
@NoArgsConstructor
public class SavedAddress extends MutableBaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", length = 10, nullable = false)
    private AddressLabel label;

    /** Optional friendly name ("Mom's place"); a user may save without naming it. */
    @Column(name = "save_as", length = 100)
    private String saveAs;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "contact_phone", length = 15)
    private String contactPhone;

    @Column(name = "house_floor", length = 200)
    private String houseFloor;

    @Column(name = "building_street", length = 200)
    private String buildingStreet;

    @Column(name = "area_locality", length = 300)
    private String areaLocality;

    @Column(name = "line1", length = 200, nullable = false)
    private String line1;

    @Column(name = "line2", length = 200)
    private String line2;

    @Column(name = "city", length = 100, nullable = false)
    private String city;

    @Column(name = "pincode", length = 10, nullable = false)
    private String pincode;

    @Column(name = "state", length = 100, nullable = false)
    private String state;

    @Column(name = "landmark", length = 200)
    private String landmark;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "delivery_instructions", length = 500)
    private String deliveryInstructions;

    /** Projects this saved row into the embeddable {@link Address} used by booking/cart. */
    public Address toAddress() {
        Address a = new Address();
        a.setHouseFloor(houseFloor);
        a.setBuildingStreet(buildingStreet);
        a.setAreaLocality(areaLocality);
        a.setLine1(line1);
        a.setLine2(line2);
        a.setCity(city);
        a.setPincode(pincode);
        a.setState(state);
        a.setLandmark(landmark);
        a.setLatitude(latitude);
        a.setLongitude(longitude);
        return a;
    }
}
