package com.openjiuwen.rdc.spi.registry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field, parameter, or return value that is legitimately {@code null}
 * in some code path. Defined locally so the {@code spi.registry} package stays
 * pure Java (no {@code jakarta.annotation} / {@code org.springframework.lang}
 * import crosses the SPI boundary — ADR-0160 decision 1).
 *
 * <p>Used on {@link AgentCardDto}'s business definition fields
 * ({@code agentName} / {@code agentType} / {@code systemProfile} /
 * {@code toolSchemas}) to signal that Method A populates them while Method B
 * leaves them {@code null} (ADR-0160 decision 2 / RB6).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface Nullable {
}
