package com.plumery.workflow.testworkflow.otel

import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped

/**
 * Promotes OTel Baggage entries to span attributes on every new span.
 *
 * This ensures that [CorrelationFilter.CORRELATION_HEADER] appears as a
 * searchable tag on every span in Jaeger — including Temporal workflow,
 * activity, and outbound REST client spans.
 *
 * Quarkus OTel automatically picks up any CDI bean implementing [SpanProcessor].
 * [@Unremovable] prevents build-time removal since no application code injects this bean directly.
 */
@ApplicationScoped
@Unremovable
class BaggageSpanProcessor : SpanProcessor {

    private val keysToPromote = listOf(CorrelationFilter.CORRELATION_HEADER)

    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        val baggage = Baggage.fromContext(parentContext)
        keysToPromote.forEach { key ->
            baggage.getEntryValue(key)?.let { value ->
                span.setAttribute(key, value)
            }
        }
    }

    override fun isStartRequired(): Boolean = true
    override fun isEndRequired(): Boolean = false
    override fun onEnd(span: ReadableSpan) = Unit
}
