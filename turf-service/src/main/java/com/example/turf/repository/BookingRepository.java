package com.example.turf.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.turf.model.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByStatusAndSlotIdIn(String status, Collection<Long> slotIds);

    List<Booking> findByUsernameOrderByCreatedAtDescIdDesc(String username);
}
