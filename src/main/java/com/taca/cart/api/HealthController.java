
package com.taca.cart.api;

import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {
    private final MongoOperations mongoOperations;

    public HealthController(MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    @GetMapping("/live")
    public ResponseEntity<HealthStatus> live() {
        return ResponseEntity.ok(new HealthStatus("UP"));
    }

    @GetMapping("/ready")
    public ResponseEntity<HealthStatus> ready() {
        try {
            Document result = mongoOperations.executeCommand(new Document("hello", 1));
            if (Boolean.TRUE.equals(result.getBoolean("isWritablePrimary"))) {
                return ResponseEntity.ok(new HealthStatus("UP"));
            }
        } catch (DataAccessException | MongoException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthStatus("DOWN"));
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthStatus("DOWN"));
    }

    public record HealthStatus(String status) {}
}
