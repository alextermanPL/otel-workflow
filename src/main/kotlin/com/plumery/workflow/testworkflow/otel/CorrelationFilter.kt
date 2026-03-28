package com.plumery.workflow.testworkflow.otel

import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import java.util.UUID

/**
 * JAX-RS filter that extracts (or generates) a correlation ID from the incoming
 * [CORRELATION_HEADER] header and propagates it as OTel Baggage.
 *
 * Once in baggage:
 *  - [BaggageSpanProcessor] promotes it to a span attribute on every new span
 *  - quarkus-temporal serialises the full OTel context (trace + baggage) into
 *    Temporal workflow headers, so it reaches every activity automatically
 *  - @RestClient calls carry the `baggage` HTTP header to downstream services
 *  - quarkus-messaging-kafka injects it into Kafka message headers on produce
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
class CorrelationFilter : ContainerRequestFilter, ContainerResponseFilter {

    companion object {
        const val CORRELATION_HEADER = "x-request-id"
        private const val SCOPE_KEY = "correlation-otel-scope"
    }

    override fun filter(requestContext: ContainerRequestContext) {
        val correlationId = requestContext.getHeaderString(CORRELATION_HEADER)
            ?: UUID.randomUUID().toString()

        // Tag the current (HTTP server) span directly
        Span.current().setAttribute(CORRELATION_HEADER, correlationId)

        // Inject into OTel Baggage — propagates to Temporal, REST clients, Kafka
        val updatedBaggage = Baggage.current().toBuilder()
            .put(CORRELATION_HEADER, correlationId)
            .build()
        val scope: Scope = Context.current().with(updatedBaggage).makeCurrent()
        requestContext.setProperty(SCOPE_KEY, scope)
    }

    override fun filter(
        requestContext: ContainerRequestContext,
        responseContext: ContainerResponseContext,
    ) {
        (requestContext.getProperty(SCOPE_KEY) as? Scope)?.close()
    }
}
