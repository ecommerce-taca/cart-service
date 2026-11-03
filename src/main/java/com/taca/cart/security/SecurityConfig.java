package com.taca.cart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${CART_GATEWAY_TOKEN:}") String gatewayToken,
            @Value("${CART_INTERNAL_TOKEN:}") String internalToken) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityErrorWriter.unauthorized(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                SecurityErrorWriter.forbidden(request, response)))
                .addFilterBefore(
                        new GatewayBuyerAuthenticationFilter(gatewayToken),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new InternalServiceAuthenticationFilter(internalToken),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health/live", "/health/ready").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/cart").hasAuthority(CartAuthorities.BUYER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/cart/items").hasAuthority(CartAuthorities.BUYER)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/cart/items/{itemId}").hasAuthority(CartAuthorities.BUYER)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/cart/items/{itemId}").hasAuthority(CartAuthorities.BUYER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/carts/{buyerUserId}").hasAuthority(CartAuthorities.INTERNAL)
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/carts/{buyerUserId}/checked-out").hasAuthority(CartAuthorities.INTERNAL)
                        .anyRequest().denyAll()
                );

        return http.build();
    }
}
