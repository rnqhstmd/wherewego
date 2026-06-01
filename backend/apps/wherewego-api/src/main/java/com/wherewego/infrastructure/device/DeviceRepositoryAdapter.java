package com.wherewego.infrastructure.device;

import com.wherewego.domain.device.Device;
import com.wherewego.domain.device.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DeviceRepositoryAdapter implements DeviceRepository {

    private final DeviceJpaRepository deviceJpa;

    @Override
    public Device save(Device device) {
        return deviceJpa.save(device);
    }

    @Override
    public Optional<Device> findActiveByUserIdAndToken(Long userId, String deviceToken) {
        return deviceJpa.findByUserIdAndDeviceTokenAndDeletedAtIsNull(userId, deviceToken);
    }

    @Override
    public List<Device> findActiveByDeviceToken(String deviceToken) {
        return deviceJpa.findByDeviceTokenAndDeletedAtIsNull(deviceToken);
    }

    @Override
    public List<Device> findActiveByUserId(Long userId) {
        return deviceJpa.findByUserIdAndDeletedAtIsNull(userId);
    }

    @Override
    public void softDeleteByUserIdAndToken(Long userId, String deviceToken) {
        deviceJpa.softDeleteByUserIdAndToken(userId, deviceToken, ZonedDateTime.now());
    }

    @Override
    public void softDeleteByToken(String deviceToken) {
        deviceJpa.softDeleteByDeviceToken(deviceToken, ZonedDateTime.now());
    }

    @Override
    public void softDeleteByUserId(Long userId) {
        deviceJpa.softDeleteByUserId(userId, ZonedDateTime.now());
    }

    @Override
    public void touch(Long deviceId) {
        deviceJpa.touchById(deviceId, ZonedDateTime.now());
    }
}
