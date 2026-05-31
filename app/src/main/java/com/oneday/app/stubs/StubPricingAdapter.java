package com.oneday.app.stubs;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("!prod")
class StubPricingAdapter implements PricingPort {

    // ₹35/kg intercity, ₹20/kg same-city, min ₹50/₹30. B2B gets 15% off base. GST 18%.
    @Override
    public QuoteResult computeQuote(QuoteRequest request) {
        boolean intercity = request.deliveryType() == DeliveryType.INTERCITY;
        double ratePerGram = intercity ? 0.035 : 0.020; // paise per gram
        long minBase = intercity ? 5000L : 3000L;       // paise

        long base = Math.max(minBase, (long) (request.chargeableWeightGrams() * ratePerGram));

        if (request.customerType() == CustomerType.B2B) {
            base = Math.round(base * 0.85);
        }

        long gst   = base * 18 / 100;
        long total = base + gst;

        return new QuoteResult(
                base,
                gst,
                total,
                Map.of("base_freight", base, "gst_18pct", gst),
                "v1.0-stub"
        );
    }
}
