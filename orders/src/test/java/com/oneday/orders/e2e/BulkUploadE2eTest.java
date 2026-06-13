package com.oneday.orders.e2e;

import com.oneday.orders.domain.Address;
import com.oneday.orders.dto.BulkPickup;
import com.oneday.orders.service.BulkUploadService;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the bulk upload (one pickup → many destinations): valid destination
 * rows are added to the cart with the shared pickup applied, invalid rows are reported, the header
 * is validated, the template downloads, and C2C is forbidden.
 */
class BulkUploadE2eTest extends OrdersE2eSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired BulkUploadService bulkUploadService;

    // A destination row: receiver_name, receiver_phone, receiver_email, dest_line1, dest_line2,
    // dest_city, dest_pincode, dest_state, weight_grams, length_cm, width_cm, height_cm, declared_value_inr
    private String[] destRow(String receiver, String pincode) {
        return new String[]{
                receiver, "+919000000002", "",
                "1 MG Road", "", "Bengaluru", pincode, "Karnataka",
                "1000", "20", "15", "10", "500"};
    }

    private byte[] xlsx(List<String[]> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(out, "test", "1.0")) {
            Worksheet ws = wb.newWorksheet("Sheet1");
            for (int r = 0; r < rows.size(); r++) {
                String[] cells = rows.get(r);
                for (int c = 0; c < cells.length; c++) ws.value(r, c, cells[c]);
            }
        }
        return out.toByteArray();
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "dest.xlsx", XLSX, bytes);
    }

    private String pickupJson() throws Exception {
        Address origin = addr("1 Connaught Place", "Delhi", "110001", "DL");
        origin.setLatitude(28.6139); origin.setLongitude(77.2090);
        return json.writeValueAsString(new BulkPickup("Acme Warehouse", "+919000000010", null, origin, "DEL", "110001"));
    }

    @Test
    void upload_addsValidDestinations_reportsInvalidRows() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());

        List<String[]> rows = new ArrayList<>();
        rows.add(bulkUploadService.headers().toArray(new String[0]));
        rows.add(destRow("Priya Sharma", "560001"));    // valid (Bengaluru prefix)
        String[] badPincode = destRow("Amit Verma", "999001");
        rows.add(badPincode);                            // pincode not in a serviceable city
        String[] missingReceiver = destRow("", "560002");
        rows.add(missingReceiver);                       // receiver_name blank → validation failure

        mvc.perform(multipart("/api/v1/bulk/upload")
                        .file(file(xlsx(rows)))
                        .param("pickup", pickupJson())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added", is(1)))
                .andExpect(jsonPath("$.failed", is(2)));

        // the one valid destination is now in the cart
        mvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_count", is(1)));
    }

    @Test
    void upload_headerMismatch_returns422() throws Exception {
        String token = tokenFor("B2B_USER", randomUserId());
        List<String[]> rows = new ArrayList<>();
        String[] wrong = bulkUploadService.headers().toArray(new String[0]);
        wrong[0] = "name";
        rows.add(wrong);
        rows.add(destRow("Priya", "560001"));
        mvc.perform(multipart("/api/v1/bulk/upload")
                        .file(file(xlsx(rows)))
                        .param("pickup", pickupJson())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void upload_c2c_isForbidden() throws Exception {
        String token = tokenFor("C2C_CUSTOMER", randomUserId());
        List<String[]> rows = new ArrayList<>();
        rows.add(bulkUploadService.headers().toArray(new String[0]));
        rows.add(destRow("Priya", "560001"));
        mvc.perform(multipart("/api/v1/bulk/upload")
                        .file(file(xlsx(rows)))
                        .param("pickup", pickupJson())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void template_downloads_asXlsx() throws Exception {
        String token = tokenFor("B2B_USER", randomUserId());
        mvc.perform(get("/api/v1/bulk/template").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("oneday-destinations-template.xlsx")));
    }
}
