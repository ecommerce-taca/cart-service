
package com.taca.cart.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Component
public class OutboundHttpTracing {
    private static final String REQUEST_ID = "X-Request-ID";
    private static final TextMapSetter<HttpRequest.Builder> SETTER =
            (carrier, key, value) -> {
                if (carrier != null) carrier.setHeader(key, value);
            };

    private final OpenTelemetry telemetry;
    private final Tracer tracer;

    public OutboundHttpTracing(OpenTelemetry telemetry) {
        this.telemetry = telemetry;
        this.tracer = telemetry.getTracer("com.taca.cart");
    }

    public HttpResponse<String> send(
            HttpClient client,
            HttpRequest.Builder builder,
            String spanName,
            String routeTemplate) throws IOException, InterruptedException {

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .setAttribute("url.template", routeTemplate)
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            telemetry.getPropagators().getTextMapPropagator()
                    .inject(Context.current(), builder, SETTER);

            String requestId = MDC.get("request_id");
            if (requestId != null
                    && requestId.matches("[A-Za-z0-9._:-]{1,64}")) {
                builder.setHeader(REQUEST_ID, requestId);
            }

            HttpResponse<String> response = client.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            span.setAttribute("http.response.status_code", response.statusCode());
            if (response.statusCode() >= 400) {
                span.setStatus(StatusCode.ERROR);
            }

            return response;
        } catch (IOException | InterruptedException ex) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", ex.getClass().getSimpleName());
            throw ex;
        } catch (RuntimeException ex) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            span.end();
        }
    }
}
