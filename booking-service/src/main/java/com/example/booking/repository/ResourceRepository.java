package com.example.booking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.booking.model.Resource;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    List<Resource> findAllByOrderByNameAscIdAsc();
}
