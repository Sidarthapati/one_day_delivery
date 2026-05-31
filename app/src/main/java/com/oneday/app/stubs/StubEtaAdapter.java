package com.oneday.app.stubs;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.EtaPort;
import com.oneday.common.port.dto.EtaRequest;
import com.oneday.common.port.dto.EtaResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
@Profile("!prod")
class StubEtaAdapter implements EtaPort {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Intercity: next-day 14:00 IST. Same-city: today 20:00 IST (next-day if already past).
    @Override
    public EtaResult fetchEta(EtaRequest request) {
        boolean intercity = request.context().deliveryType() == DeliveryType.INTERCITY;

        ZonedDateTime now = ZonedDateTime.now(IST);
        ZonedDateTime eta;
        int slaMinutes;

        if (intercity) {
            eta        = LocalDate.now(IST).plusDays(1).atTime(14, 0).atZone(IST);
            slaMinutes = 1440;
        } else {
            eta        = LocalDate.now(IST).atTime(20, 0).atZone(IST);
            if (now.isAfter(eta)) eta = eta.plusDays(1);
            slaMinutes = 480;
        }

        return new EtaResult(eta.toInstant(), slaMinutes);
    }
}
