package com.taca.cart.api;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CartHealthRecoveryTest {
    private MongoOperations mongoOperations;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mongoOperations = mock(MongoOperations.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new HealthController(mongoOperations)).build();
    }

    @Test
    void shouldRecoverReadinessWithoutRestartingApplication()
            throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenThrow(new DataAccessResourceFailureException(
                        "MongoDB unavailable"))
                .thenReturn(new Document("isWritablePrimary", true));

        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));

        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        verify(mongoOperations, times(2))
                .executeCommand(any(Document.class));
    }

    @Test
    void shouldNotExposeMongoFailureDetailsInHealthResponse()
            throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenThrow(new DataAccessResourceFailureException(
                        "Sensitive MongoDB connection information"));

        var result = mvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertFalse(body.contains("Sensitive MongoDB"));
        assertEquals("{\"status\":\"DOWN\"}", body);

        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
