package com.example.turf.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.turf.model.Slot;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findByVenueIdInOrderByStartTimeAscIdAsc(Collection<Long> venueIds);
}
