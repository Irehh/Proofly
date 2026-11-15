package com.proofly.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI prooflyOpenAPI() {
        final String bearerScheme = "bearerJwt";
        return new OpenAPI()
                .info(new Info()
                        .title("Proofly Geofenced Attendance API")
                        .version("1.0.0-p0")
                        .description("Multi-tenant geofenced staff attendance SaaS API. OpenAPI 3.1 contract-first specification.")
                        .contact(new Contact().name("Proofly Engineering").email("engineering@proofly.com")))
                .addSecurityItem(new SecurityRequirement().addList(bearerScheme))
                .components(new Components().addSecuritySchemes(bearerScheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived RS256 access token (<=15m) carrying sub, tid, mid, role claims.")));
    }
}
