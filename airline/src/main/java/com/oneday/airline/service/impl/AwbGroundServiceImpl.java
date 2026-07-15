package com.oneday.airline.service.impl;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.service.AwbGroundService;
import com.oneday.airline.service.exception.AwbNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** The two ground-crew confirmations (§9), each just a timestamp recorded for later reporting. */
@Service
class AwbGroundServiceImpl implements AwbGroundService {

    private final AwbRepository awbRepository;
    private final Clock clock;

    AwbGroundServiceImpl(AwbRepository awbRepository, Clock clock) {
        this.awbRepository = awbRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Awb handOver(UUID awbId) {
        Awb awb = requireAwb(awbId);
        awb.setHandedOverAt(clock.instant());
        return awbRepository.save(awb);
    }

    @Override
    @Transactional
    public Awb markLoaded(UUID awbId) {
        Awb awb = requireAwb(awbId);
        awb.setLoadedAt(clock.instant());
        return awbRepository.save(awb);
    }

    private Awb requireAwb(UUID awbId) {
        return awbRepository.findById(awbId).orElseThrow(() -> new AwbNotFoundException(awbId));
    }
}
