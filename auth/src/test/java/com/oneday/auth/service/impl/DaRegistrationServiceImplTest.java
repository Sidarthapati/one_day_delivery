package com.oneday.auth.service.impl;

import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.RegisterDaRequest;
import com.oneday.auth.dto.request.RegisterUserRequest;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UnknownCityException;
import com.oneday.auth.repository.DaProfileRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.UserService;
import com.oneday.common.domain.Shift;
import com.oneday.common.port.CityDirectoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #217: a DA's {@code users.city_id} must be stored as the canonical city CODE the roster matches,
 * whatever form the caller passes — otherwise the DA is silently never loadable onto a shift.
 */
@ExtendWith(MockitoExtension.class)
class DaRegistrationServiceImplTest {

    @Mock UserService userService;
    @Mock UserRepository userRepository;
    @Mock DaProfileRepository daProfileRepository;
    @Mock CityDirectoryPort cityDirectory;

    private DaRegistrationServiceImpl service() {
        return new DaRegistrationServiceImpl(userService, userRepository, daProfileRepository, cityDirectory);
    }

    private RegisterDaRequest request(String cityId) {
        return new RegisterDaRequest("Ravi", "Kadam", "ravi@oneday.in", "9990001111", cityId,
                Shift.SHIFT_1, LocalDate.of(2026, 1, 1), null, null, null, null, "Secret123!");
    }

    @Test
    void register_normalisesUuidCityIdToCanonicalCode() {
        String bomUuid = UUID.randomUUID().toString();
        UUID newUserId = UUID.randomUUID();
        when(cityDirectory.toCityCode(bomUuid)).thenReturn(Optional.of("mumbai"));
        when(userService.register(any(), any())).thenReturn(
                new UserResponse(newUserId, "ravi@oneday.in", "Ravi Kadam", "DELIVERY_ASSOCIATE", "mumbai", true));
        when(userRepository.findById(newUserId)).thenReturn(Optional.of(new User()));

        DaResponse response = service().register(request(bomUuid), UUID.randomUUID());

        // The user-creation seam must receive the canonical code, not the UUID.
        ArgumentCaptor<RegisterUserRequest> captor = ArgumentCaptor.forClass(RegisterUserRequest.class);
        verify(userService).register(captor.capture(), any());
        assertThat(captor.getValue().cityId()).isEqualTo("mumbai");
        assertThat(response.cityId()).isEqualTo("mumbai");
    }

    @Test
    void register_rejectsUnresolvableCity() {
        when(cityDirectory.toCityCode("atlantis")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(request("atlantis"), UUID.randomUUID()))
                .isInstanceOf(UnknownCityException.class);

        verify(userService, never()).register(any(), any());
    }
}
