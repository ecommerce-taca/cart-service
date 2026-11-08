package com.taca.cart.observability;

import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=false"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartHttpMetricsIntegrationTest {
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @MockitoBean
    private ProductHydrationClient productHydrationClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @Test
    void shouldRecordHttpMetricsForHealthEndpoint() throws Exception {
        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        Timer timer = meterRegistry.find("http.server.requests")
                .tags("method", "GET", "uri", "/health/live", "status", "200")
                .timer();

        assertNotNull(timer);
        assertTrue(timer.count() >= 1);
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0);
    }

    @Test
    void shouldUseRouteTemplateInsteadOfBuyerIdAsMetricTag()
            throws Exception {
        mvc.perform(get("/api/v1/internal/carts/buyer-001")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk());

        Timer timer = meterRegistry.find("http.server.requests")
                .tags(
                        "method", "GET",
                        "uri", "/api/v1/internal/carts/{buyerUserId}",
                        "status", "200")
                .timer();

        assertNotNull(timer);
        assertTrue(timer.count() >= 1);

        assertNull(meterRegistry.find("http.server.requests")
                .tag("uri", "/api/v1/internal/carts/buyer-001")
                .timer());
    }
}
