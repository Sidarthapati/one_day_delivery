package com.oneday.orders.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Address {
    // Granular components from the two-step map capture (Swiggy-style). All optional —
    // persisted inside the address JSONB blob, no schema column needed.
    @Size(max = 200) private String houseFloor;
    @Size(max = 200) private String buildingStreet;
    @Size(max = 300) private String areaLocality;

    @NotBlank @Size(max = 200) private String line1;
    @Size(max = 200) private String line2;
    @NotBlank @Size(max = 100) private String city;
    @NotBlank @Size(max = 10)  private String pincode;
    @NotBlank @Size(max = 100) private String state;
    @Size(max = 200) private String landmark;

    // WGS84 pin from the booking map (optional). Bounds loosely clamp to the
    // Indian mainland; null when the caller didn't pick a point on the map.
    // Persisted inside the address JSONB blob — no schema column needed.
    @DecimalMin("6.0") @DecimalMax("38.0")  private Double latitude;
    @DecimalMin("68.0") @DecimalMax("98.0") private Double longitude;
}
