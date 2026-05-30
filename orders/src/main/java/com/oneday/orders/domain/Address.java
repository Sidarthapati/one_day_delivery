package com.oneday.orders.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    @NotBlank @Size(max = 200) private String line1;
    @Size(max = 200) private String line2;
    @NotBlank @Size(max = 100) private String city;
    @NotBlank @Size(max = 10)  private String pincode;
    @NotBlank @Size(max = 100) private String state;
    @Size(max = 200) private String landmark;
}
