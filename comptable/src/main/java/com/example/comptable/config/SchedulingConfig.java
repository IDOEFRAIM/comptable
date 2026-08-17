package com.example.comptable.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active le support {@code @Scheduled} (utilise par {@code RelanceScheduler}, Sprint 5). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
