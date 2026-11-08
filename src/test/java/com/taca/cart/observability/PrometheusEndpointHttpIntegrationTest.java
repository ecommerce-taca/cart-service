package com.taca.cart.observability;

import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "cart.indexes.initialize=false",
                "management.endpoints.web.exposure.include=prometheus",
                "management.prometheus.metrics.export.enabled=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrometheusEndpointHttpIntegrationTest {
    private static final String PATH = "/actuator/prometheus";
    private static final String MONITORING_TOKEN = "c".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7.0").withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("CART_MONITORING_TOKEN", () -> MONITORING_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CartMetrics cartMetrics;

    @Autowired
    private CartDependencyMetrics dependencyMetrics;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @MockitoBean
    private ProductHydrationClient productHydrationClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @Test
    void shouldExposePrometheusMetricsWithValidMonitoringToken()
            throws Exception {
        cartMetrics.recordMutation(
                CartMetrics.MutationResult.UPDATED,
                TimeUnit.MILLISECONDS.toNanos(12));

        cartMetrics.recordConflict(CartMetrics.CasCause.VERSION);

        dependencyMetrics.recordProductHttp(
                CartDependencyMetrics.ProductHttpOutcome.TIMEOUT,
                TimeUnit.MILLISECONDS.toNanos(200));

        dependencyMetrics.recordInventorySkipped(
                CartDependencyMetrics.InventorySkipReason.BUSY);

        var result = mvc.perform(get(PATH)
                        .header("Authorization", bearer(MONITORING_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.TEXT_PLAIN))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertTrue(body.contains("cart_mutations"),
                "Missing cart mutation metrics");
        assertTrue(body.contains("cart_cas_conflicts"),
                "Missing CAS metrics");
        assertTrue(body.contains("cart_product_http_requests"),
                "Missing Product HTTP metrics");
        assertTrue(body.contains("cart_inventory_skipped"),
                "Missing Inventory skip metrics");
        assertTrue(body.contains("cart_hydration_items"),
                "Missing hydration metrics");
        assertTrue(body.contains("jvm_"),
                "Missing JVM metrics");

        assertFalse(body.contains(MONITORING_TOKEN));
        assertFalse(body.contains(GATEWAY_TOKEN));
        assertFalse(body.contains(INTERNAL_TOKEN));

        assertEquals(1.0, meterRegistry.get("cart.mutations")
                .tag("result", "updated").counter().count());
    }

    @Test
    void shouldRejectMissingMonitoringToken() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectInvalidMonitoringToken() throws Exception {
        for (String authorization : new String[]{
                bearer("d".repeat(64)),
                bearer("c".repeat(63)),
                "Basic " + MONITORING_TOKEN,
                "Bearer invalid-token"
        }) {
            mvc.perform(get(PATH)
                            .header("Authorization", authorization))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code")
                            .value("CART_UNAUTHENTICATED"));
        }
    }

    @Test
    void shouldRejectDuplicateAuthorizationHeaders() throws Exception {
        mvc.perform(get(PATH)
                        .header("Authorization",
                                bearer(MONITORING_TOKEN),
                                bearer(MONITORING_TOKEN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotAcceptBuyerOrCheckoutTokenAsMonitoringToken()
            throws Exception {
        mvc.perform(get(PATH)
                        .header("Authorization", bearer(GATEWAY_TOKEN)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH)
                        .header("Authorization", bearer(INTERNAL_TOKEN)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoExternalCalls();
    }

    @Test
    void shouldNotExposeSensitiveActuatorEndpoints() throws Exception {
        for (String path : new String[]{
                "/actuator/env",
                "/actuator/beans",
                "/actuator/configprops",
                "/actuator/heapdump",
                "/actuator/metrics"
        }) {
            mvc.perform(get(path)
                            .header("Authorization", bearer(MONITORING_TOKEN)))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void shouldPreserveHealthContracts() throws Exception {
        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void verifyNoExternalCalls() {
        org.mockito.Mockito.verifyNoInteractions(
                productCatalogClient,
                productHydrationClient,
                inventoryClient);
    }
}
