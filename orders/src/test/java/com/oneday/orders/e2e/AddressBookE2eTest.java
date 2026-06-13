package com.oneday.orders.e2e;

import com.oneday.orders.domain.enums.AddressLabel;
import com.oneday.orders.dto.SavedAddressRequest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the saved-address book: real HTTP → controller → service →
 * Postgres (rolled back). Verifies CRUD, optional save-as, validation, 404, role gating,
 * and cross-user isolation.
 */
class AddressBookE2eTest extends OrdersE2eSupport {

    private SavedAddressRequest homeAddress() {
        SavedAddressRequest r = new SavedAddressRequest();
        r.setLabel(AddressLabel.HOME);
        r.setContactName("Addr User");
        r.setContactPhone("+919811000000");
        r.setHouseFloor("Flat G06");
        r.setBuildingStreet("Vajra Jasmine County");
        r.setAreaLocality("Nanakramguda");
        r.setLine1("Block C, Vajra Jasmine County");
        r.setCity("Hyderabad");
        r.setPincode("500032");
        r.setState("Telangana");
        r.setLatitude(17.4156);
        r.setLongitude(78.3414);
        r.setDeliveryInstructions("Ring the bell");
        return r;
    }

    private String createReturningId(String token, SavedAddressRequest req) throws Exception {
        String body = mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void createListUpdateDelete_happyPath() throws Exception {
        String token = tokenFor("C2C_CUSTOMER", randomUserId());

        // CREATE — no save_as supplied (it is optional)
        String createBody = mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(homeAddress())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label", is("HOME")))
                .andExpect(jsonPath("$.save_as", nullValue()))
                .andExpect(jsonPath("$.house_floor", is("Flat G06")))
                .andExpect(jsonPath("$.area_locality", is("Nanakramguda")))
                .andExpect(jsonPath("$.latitude", is(17.4156)))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(createBody).get("id").asText();

        // LIST
        mvc.perform(get("/api/v1/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(id)));

        // UPDATE → OFFICE + save_as
        SavedAddressRequest upd = homeAddress();
        upd.setLabel(AddressLabel.OFFICE);
        upd.setSaveAs("HQ");
        mvc.perform(put("/api/v1/addresses/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(upd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label", is("OFFICE")))
                .andExpect(jsonPath("$.save_as", is("HQ")));

        // DELETE
        mvc.perform(delete("/api/v1/addresses/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // LIST empty again
        mvc.perform(get("/api/v1/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void create_invalidPincode_returns422() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        SavedAddressRequest bad = homeAddress();
        bad.setPincode("ABC");
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(bad)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        String token = tokenFor("B2B_USER", randomUserId());
        mvc.perform(put("/api/v1/addresses/" + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(homeAddress())))
                .andExpect(status().isNotFound());
    }

    @Test
    void addresses_areIsolatedPerUser() throws Exception {
        String userA = tokenFor("C2C_CUSTOMER", randomUserId());
        String userB = tokenFor("C2C_CUSTOMER", randomUserId());
        createReturningId(userA, homeAddress());

        // user B sees none of A's addresses
        mvc.perform(get("/api/v1/addresses").header("Authorization", "Bearer " + userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void admin_isForbidden_fromAddressBook() throws Exception {
        String admin = tokenFor("ADMIN", randomUserId());
        mvc.perform(get("/api/v1/addresses").header("Authorization", "Bearer " + admin))
                .andExpect(status().isForbidden());
    }
}
