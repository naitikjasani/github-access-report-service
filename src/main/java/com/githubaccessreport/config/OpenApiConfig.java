package com.githubaccessreport.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("GitHub Access Report API")
                        .version("v1")
                        .description("Who can access which repositories in a GitHub organization, "
                                + "and what the report could not see. Errors are RFC 9457 problem details.")
                        .license(new License().name("MIT License").url("https://opensource.org/licenses/MIT")));
    }
}
