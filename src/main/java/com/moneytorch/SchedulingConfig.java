package com.moneytorch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Periodic collectors only run in server mode; export mode calls them once itself. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "moneytorch", name = "mode", havingValue = "server", matchIfMissing = true)
public class SchedulingConfig {
}
