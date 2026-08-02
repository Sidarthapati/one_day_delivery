package com.oneday.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Merchant white-label branding for the public tracking page. All fields optional. */
public class BrandingRequest {

    @Size(max = 120) private String brandName;
    @Size(max = 20)  private String brandColor;     // hex, e.g. #0F172A
    @Size(max = 500) private String brandLogoUrl;
    @Size(max = 254) @Email private String supportEmail;
    @Size(max = 20)  private String supportPhone;

    public String getBrandName()             { return brandName; }
    public void setBrandName(String v)       { this.brandName = v; }

    public String getBrandColor()            { return brandColor; }
    public void setBrandColor(String v)      { this.brandColor = v; }

    public String getBrandLogoUrl()          { return brandLogoUrl; }
    public void setBrandLogoUrl(String v)    { this.brandLogoUrl = v; }

    public String getSupportEmail()          { return supportEmail; }
    public void setSupportEmail(String v)    { this.supportEmail = v; }

    public String getSupportPhone()          { return supportPhone; }
    public void setSupportPhone(String v)    { this.supportPhone = v; }
}
