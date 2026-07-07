package com.example.turf.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.turf.model.Venue;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    List<Venue> findAllByOrderByNameAscIdAsc();
}
