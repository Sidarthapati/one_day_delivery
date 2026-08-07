package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaProfile;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.RegisterDaRequest;
import com.oneday.auth.dto.request.RegisterUserRequest;
import com.oneday.auth.dto.request.UpdateDaRequest;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.repository.DaProfileRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.DaRegistrationService;
import com.oneday.auth.service.UserService;
import com.oneday.common.domain.Shift;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
class DaRegistrationServiceImpl implements DaRegistrationService {

    private static final String DELIVERY_ASSOCIATE = "DELIVERY_ASSOCIATE";
    private static final char[] PW_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();

    private final UserService userService;
    private final UserRepository userRepository;
    private final DaProfileRepository daProfileRepository;

    DaRegistrationServiceImpl(UserService userService,
                              UserRepository userRepository,
                              DaProfileRepository daProfileRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.daProfileRepository = daProfileRepository;
    }

    @Override
    @Transactional
    public DaResponse register(RegisterDaRequest request, UUID actorId) {
        boolean generated = request.password() == null || request.password().isBlank();
        String password = generated ? generateTempPassword() : request.password();
        String name = (request.firstName() + " " + request.lastName()).trim();

        // Reuse the audited user-creation seam (role validation, city-scope, mustChangePassword=true).
        UserResponse created = userService.register(
                new RegisterUserRequest(name, request.email(), password, DELIVERY_ASSOCIATE, request.cityId()),
                actorId);

        // Phone lives on the user; register() doesn't take it, so set it here in the same transaction.
        User user = userRepository.findById(created.id())
                .orElseThrow(() -> new UserNotFoundException("User not found after create"));
        user.setPhone(request.phone());
        userRepository.save(user);

        DaProfile profile = new DaProfile();
        profile.setUserId(created.id());
        profile.setFirstName(request.firstName());
        profile.setLastName(request.lastName());
        profile.setAadhaar(request.aadhaar());
        profile.setPan(request.pan());
        profile.setPanDocUrl(request.panDocUrl());
        profile.setContractStartDate(request.contractStartDate());
        profile.setContractEndDate(request.contractEndDate());
        profile.setShift(request.shift());
        daProfileRepository.save(profile);

        return new DaResponse(created.id(), name, request.email(), request.phone(), request.cityId(),
                request.shift(), request.contractStartDate(), request.contractEndDate(), true,
                generated ? password : null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaResponse> list(String cityId, Shift shift, Boolean active) {
        return daProfileRepository.findDaSummaries(cityId, shift == null ? null : shift.name(), active)
                .stream()
                .map(s -> new DaResponse(s.getDaId(), s.getName(), s.getEmail(), s.getPhone(),
                        s.getCityId(), Shift.valueOf(s.getShift()), s.getContractStartDate(),
                        s.getContractEndDate(), s.getActive(), null))
                .toList();
    }

    @Override
    @Transactional
    public DaResponse update(UUID daId, UpdateDaRequest request) {
        DaProfile profile = daProfileRepository.findById(daId)
                .orElseThrow(() -> new UserNotFoundException("DA profile not found: " + daId));
        User user = userRepository.findById(daId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + daId));

        profile.setFirstName(request.firstName());
        profile.setLastName(request.lastName());
        profile.setShift(request.shift());
        profile.setContractStartDate(request.contractStartDate());
        profile.setContractEndDate(request.contractEndDate());
        profile.setAadhaar(request.aadhaar());
        profile.setPan(request.pan());
        profile.setPanDocUrl(request.panDocUrl());
        daProfileRepository.save(profile);

        String name = (request.firstName() + " " + request.lastName()).trim();
        user.setName(name);
        user.setPhone(request.phone());
        userRepository.save(user);

        return new DaResponse(daId, name, user.getEmail(), user.getPhone(), user.getCityId(),
                request.shift(), request.contractStartDate(), request.contractEndDate(),
                user.isActive(), null);
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PW_ALPHABET[random.nextInt(PW_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
