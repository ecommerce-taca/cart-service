
package com.taca.cart.api;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    private MongoOperations mongoOperations;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mongoOperations = mock(MongoOperations.class);
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(mongoOperations)).build();
    }

    @Test
    void shouldReturnLiveWithoutMongoDb() throws Exception {
        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        verifyNoInteractions(mongoOperations);
    }

    @Test
    void shouldReturnReadyWhenMongoIsPrimary() throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenReturn(new Document("isWritablePrimary", true));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldReturnUnavailableWhenMongoIsNotPrimary() throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenReturn(new Document("isWritablePrimary", false));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void shouldReturnUnavailableWhenMongoIsDown() throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
