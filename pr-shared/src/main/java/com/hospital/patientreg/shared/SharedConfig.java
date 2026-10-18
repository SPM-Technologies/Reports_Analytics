package com.hospital.patientreg.shared;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Shared kernel beans (export writers, download limiter) used by more than one module. */
@Configuration
@ComponentScan(basePackages = "com.hospital.patientreg.shared.export")
public class SharedConfig {
}
