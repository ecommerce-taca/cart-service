
package com.taca.cart.application;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

@Component
public class UuidV7Generator {
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        long millis = clock.millis();
        if (millis < 0 || (millis >>> 48) != 0) {
            throw new IllegalStateException("Timestamp is out of UUIDv7 range");
        }

        byte[] bytes = new byte[16];
        random.nextBytes(bytes);

        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) (millis >>> (40 - i * 8));
        }

        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }
}
