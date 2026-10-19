package dev.atlas.platform;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestContext {

    private RequestContext() {}

    public static UUID correlationId() {
        String value = MDC.get("correlationId");
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }
}
