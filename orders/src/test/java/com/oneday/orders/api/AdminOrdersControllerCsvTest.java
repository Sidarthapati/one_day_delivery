package com.oneday.orders.api;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.dto.ShipmentSummaryResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOrdersControllerCsvTest {

    @Test
    void escapesCommasAndQuotesAndWritesHeaderPlusRow() {
        ShipmentSummaryResponse row = new ShipmentSummaryResponse(
                "1DD-BLR-1", CustomerType.B2C, DeliveryType.INTERCITY, ShipmentState.BOOKED,
                PickupType.DA_PICKUP, PaymentMode.PREPAID,
                "Bengaluru", "560001", "Delhi", "110001",
                "Sharma, Rao & Co", "Priya \"Pri\" N", 1200, 25000L,
                Instant.parse("2026-08-24T10:00:00Z"), null, "Bengaluru", true);

        String csv = AdminOrdersController.toCsv(List.of(row));
        String[] lines = csv.split("\n", -1);

        assertThat(lines[0]).startsWith("shipment_ref,customer_type,delivery_type,state");
        // comma-bearing sender is quoted; embedded quotes are doubled
        assertThat(lines[1]).contains("\"Sharma, Rao & Co\"");
        assertThat(lines[1]).contains("\"Priya \"\"Pri\"\" N\"");
        assertThat(lines[1]).contains("1DD-BLR-1");
        assertThat(lines[1]).contains("560001");
    }

    @Test
    void plainValuesAreUnquotedAndNullsAreEmpty() {
        assertThat(AdminOrdersController.csv("BOOKED")).isEqualTo("BOOKED");
        assertThat(AdminOrdersController.csv(null)).isEmpty();
        assertThat(AdminOrdersController.csv(42)).isEqualTo("42");
    }

    @Test
    void neutralizesFormulaLeadingCells() {
        // A leading =,+,-,@ is apostrophe-prefixed so a spreadsheet won't evaluate it (CSV injection).
        assertThat(AdminOrdersController.csv("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(AdminOrdersController.csv("+91999")).isEqualTo("'+91999");
        assertThat(AdminOrdersController.csv("-1")).isEqualTo("'-1");
        assertThat(AdminOrdersController.csv("@handle")).isEqualTo("'@handle");
        // still quoted when it also contains a comma
        assertThat(AdminOrdersController.csv("=1,2")).isEqualTo("\"'=1,2\"");
        // an ordinary value is untouched
        assertThat(AdminOrdersController.csv("Bengaluru")).isEqualTo("Bengaluru");
    }

    @Test
    void usesCrlfRecordDelimiter() {
        ShipmentSummaryResponse row = new ShipmentSummaryResponse(
                "1DD-BLR-2", CustomerType.B2C, DeliveryType.INTERCITY, ShipmentState.BOOKED,
                PickupType.DA_PICKUP, PaymentMode.PREPAID, "Bengaluru", "560001", "Delhi", "110001",
                "A", "B", 1000, 100L, Instant.parse("2026-08-24T10:00:00Z"), null, "Bengaluru", true);
        String csv = AdminOrdersController.toCsv(List.of(row));
        assertThat(csv).contains("\r\n");                 // RFC-4180 record delimiter
        assertThat(csv.split("\r\n", -1)).hasSizeGreaterThanOrEqualTo(2);
    }
}
