package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.response.TileQueueResponse;
import com.oneday.dispatch.service.StationDispatchService;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the station controller's role gate and city-scope resolution. */
class StationDispatchControllerTest {

    private StationDispatchService stationDispatchService;
    private GridService gridService;
    private StationDispatchController controller;

    private final UUID tile = UUID.randomUUID();
    private final LocalDate date = LocalDate.now();

    @BeforeEach
    void setUp() {
        stationDispatchService = mock(StationDispatchService.class);
        gridService = mock(GridService.class);
        controller = new StationDispatchController(stationDispatchService, gridService);
        when(stationDispatchService.tileQueue(any(), any(), any()))
                .thenReturn(new TileQueueResponse(tile, date, List.of(), 0));
    }

    @Test
    void adminQueriesAnyTileWithNullScope() {
        controller.tileQueue(tile, date, principal(UUID.randomUUID(), "ADMIN", null));
        verify(stationDispatchService).tileQueue(eq(tile), eq(date), isNull());
    }

    @Test
    void stationManagerScopedToTheirCity() {
        UUID cityId = UUID.randomUUID();
        controller.tileQueue(tile, date, principal(UUID.randomUUID(), "STATION_MANAGER", cityId.toString()));
        verify(stationDispatchService).tileQueue(eq(tile), eq(date), eq(cityId));
    }

    @Test
    void otherRoleIsForbidden() {
        assertThatThrownBy(() -> controller.tileQueue(tile, date,
                principal(UUID.randomUUID(), "DELIVERY_ASSOCIATE", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void unauthenticatedIs401() {
        assertThatThrownBy(() -> controller.tileQueue(tile, date, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    private AuthUserDetails principal(UUID userId, String role, String cityId) {
        AuthUserDetails p = mock(AuthUserDetails.class, RETURNS_DEEP_STUBS);
        when(p.getUserId()).thenReturn(userId);
        when(p.getUser().getRole().getName()).thenReturn(role);
        when(p.getUser().getCityId()).thenReturn(cityId);
        return p;
    }
}
