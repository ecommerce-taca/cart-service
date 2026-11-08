package com.taca.cart.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestLoggingFilterTest {
    private HttpRequestLoggingFilter filter;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        filter = new HttpRequestLoggingFilter();
        logger = (Logger) LoggerFactory.getLogger(
                HttpRequestLoggingFilter.class);

        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void shouldPreserveRequestIdAndLogCompletion() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/cart");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("X-Request-ID", "req-cart-001");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/cart");

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("req-cart-001", MDC.get("request_id"));
            ((HttpServletResponse) res).setStatus(200);
        });

        assertEquals("req-cart-001", response.getHeader("X-Request-ID"));
        assertNull(MDC.get("request_id"));

        ILoggingEvent event = singleEvent();

        assertEquals("req-cart-001",
                event.getMDCPropertyMap().get("request_id"));
        assertEquals("http.request.completed", value(event, "event"));
        assertEquals("GET", value(event, "method"));
        assertEquals("/api/v1/cart", value(event, "route"));
        assertEquals(200, value(event, "status_code"));
        assertTrue(((Number) value(event, "duration_ms")).longValue() >= 0);
    }

    @Test
    void shouldGenerateRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/health/live");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        String requestId = response.getHeader("X-Request-ID");

        assertNotNull(requestId);
        assertEquals(36, requestId.length());
        assertEquals(requestId,
                singleEvent().getMDCPropertyMap().get("request_id"));
        assertNull(MDC.get("request_id"));
    }

    @Test
    void shouldReplaceInvalidRequestId() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/health/live");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("X-Request-ID", "invalid request id");

        filter.doFilter(request, response, (req, res) -> {});

        String requestId = response.getHeader("X-Request-ID");

        assertNotNull(requestId);
        assertNotEquals("invalid request id", requestId);
        assertEquals(requestId,
                singleEvent().getMDCPropertyMap().get("request_id"));
    }

    @Test
    void shouldNotLogRawPathHeadersOrRequestBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/internal/carts/private-buyer/checked-out");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("X-Internal-Service-Token", "secret-internal-token");
        request.addHeader("Authorization", "Bearer secret-access-token");
        request.setQueryString("password=secret-query-value");
        request.setContent(
                "secret-cart-body".getBytes(StandardCharsets.UTF_8));

        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/internal/carts/{buyerUserId}/checked-out");

        filter.doFilter(request, response, (req, res) ->
                ((HttpServletResponse) res).setStatus(200));

        ILoggingEvent event = singleEvent();

        assertEquals("/api/v1/internal/carts/{buyerUserId}/checked-out",
                value(event, "route"));

        String logged = event.getFormattedMessage()
                + event.getMDCPropertyMap()
                + event.getKeyValuePairs();

        for (String secret : List.of(
                "private-buyer",
                "secret-internal-token",
                "secret-access-token",
                "secret-query-value",
                "secret-cart-body")) {
            assertFalse(logged.contains(secret), "Leaked: " + secret);
        }
    }

    @Test
    void shouldUseUnmatchedRouteWhenHandlerIsUnavailable()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/private/value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                ((HttpServletResponse) res).setStatus(404));

        ILoggingEvent event = singleEvent();

        assertEquals("UNMATCHED", value(event, "route"));
        assertEquals(404, value(event, "status_code"));
    }

    @Test
    void shouldLog500AndClearMdcWhenDownstreamThrows() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/cart");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, response,
                        (req, res) -> {
                            throw new IllegalStateException(
                                    "secret-exception-detail");
                        }));

        ILoggingEvent event = singleEvent();

        assertEquals(500, value(event, "status_code"));
        assertNull(MDC.get("request_id"));
        assertFalse(event.getFormattedMessage()
                .contains("secret-exception-detail"));
    }

    @Test
    void shouldRestorePreviousMdcValue() throws Exception {
        MDC.put("request_id", "outer-request");

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/health/live");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("X-Request-ID", "inner-request");

        filter.doFilter(request, response, (req, res) ->
                assertEquals("inner-request", MDC.get("request_id")));

        assertEquals("outer-request", MDC.get("request_id"));
        assertEquals("inner-request",
                singleEvent().getMDCPropertyMap().get("request_id"));
    }

    private ILoggingEvent singleEvent() {
        assertEquals(1, appender.list.size());
        return appender.list.getFirst();
    }

    private Object value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing log field: " + key));
    }
}
