package com.example.booking.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.booking.model.Slot;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findByResourceIdInOrderByStartTimeAscIdAsc(Collection<Long> resourceIds);
}
