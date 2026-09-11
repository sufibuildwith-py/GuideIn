package io.guidein.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("guidein.security")
public record GuideInSecurityProperties(@NotBlank String issuerUri, @NotBlank String jwkSetUri,
                                        @NotBlank String audience) {}
