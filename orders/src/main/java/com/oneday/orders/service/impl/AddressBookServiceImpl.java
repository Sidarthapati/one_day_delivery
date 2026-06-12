package com.oneday.orders.service.impl;

import com.oneday.orders.domain.SavedAddress;
import com.oneday.orders.dto.SavedAddressRequest;
import com.oneday.orders.dto.SavedAddressResponse;
import com.oneday.orders.repository.SavedAddressRepository;
import com.oneday.orders.service.AddressBookService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class AddressBookServiceImpl implements AddressBookService {

    private final SavedAddressRepository repository;

    AddressBookServiceImpl(SavedAddressRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedAddressResponse> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(SavedAddressResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public SavedAddressResponse create(UUID userId, SavedAddressRequest request) {
        SavedAddress entity = new SavedAddress();
        entity.setUserId(userId);
        apply(entity, request);
        return SavedAddressResponse.from(repository.save(entity));
    }

    @Override
    @Transactional
    public SavedAddressResponse update(UUID userId, UUID addressId, SavedAddressRequest request) {
        SavedAddress entity = repository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException("Address not found: " + addressId));
        apply(entity, request);
        return SavedAddressResponse.from(repository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID addressId) {
        SavedAddress entity = repository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException("Address not found: " + addressId));
        repository.delete(entity);
    }

    private static void apply(SavedAddress e, SavedAddressRequest r) {
        e.setLabel(r.getLabel());
        e.setSaveAs(r.getSaveAs());
        e.setContactName(r.getContactName());
        e.setContactPhone(r.getContactPhone());
        e.setHouseFloor(r.getHouseFloor());
        e.setBuildingStreet(r.getBuildingStreet());
        e.setAreaLocality(r.getAreaLocality());
        e.setLine1(r.getLine1());
        e.setLine2(r.getLine2());
        e.setCity(r.getCity());
        e.setPincode(r.getPincode());
        e.setState(r.getState());
        e.setLandmark(r.getLandmark());
        e.setLatitude(r.getLatitude());
        e.setLongitude(r.getLongitude());
        e.setDeliveryInstructions(r.getDeliveryInstructions());
    }
}
