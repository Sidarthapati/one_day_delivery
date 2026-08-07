package com.oneday.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public "Talk to sales" submission. */
public class CreateSalesLeadRequest {

    @NotBlank @Size(max = 120) private String name;
    @Size(max = 200) private String company;
    @NotBlank @Email @Size(max = 254) private String email;
    @Size(max = 20) private String phone;
    @Size(max = 20) private String monthlyVolume;
    @Size(max = 2000) private String message;

    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }

    public String getCompany()              { return company; }
    public void setCompany(String v)        { this.company = v; }

    public String getEmail()                { return email; }
    public void setEmail(String v)          { this.email = v; }

    public String getPhone()                { return phone; }
    public void setPhone(String v)          { this.phone = v; }

    public String getMonthlyVolume()        { return monthlyVolume; }
    public void setMonthlyVolume(String v)  { this.monthlyVolume = v; }

    public String getMessage()              { return message; }
    public void setMessage(String v)        { this.message = v; }
}
