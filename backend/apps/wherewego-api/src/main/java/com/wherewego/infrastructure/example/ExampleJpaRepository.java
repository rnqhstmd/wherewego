package com.wherewego.infrastructure.example;

import com.wherewego.domain.example.ExampleModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExampleJpaRepository extends JpaRepository<ExampleModel, Long> {}
