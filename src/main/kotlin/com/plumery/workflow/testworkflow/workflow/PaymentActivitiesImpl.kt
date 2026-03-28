package com.plumery.workflow.testworkflow.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.plumery.workflow.testworkflow.client.PaymentApiClient
import com.plumery.workflow.testworkflow.model.FraudCheckCommand
import com.plumery.workflow.testworkflow.model.TransferResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.quarkiverse.temporal.TemporalActivity
import io.temporal.failure.ApplicationFailure
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.rest.client.inject.RestClient

private val logger = KotlinLogging.logger {}

@TemporalActivity(workers = [WorkflowConstants.TASK_QUEUE])
class PaymentActivitiesImpl @Inject constructor(
    @RestClient private val paymentApiClient: PaymentApiClient,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
    @Channel("fraud-check-commands") private val fraudCommandEmitter: MutinyEmitter<String>,
) : PaymentActivities {

    /**
     * Sends the reservation request and returns immediately (fire & forget).
     * The bank will later call back our signal endpoint with the result.
     */
    override fun reserveFunds(paymentId: String) {
        logger.info { "Sending reserve request for payment $paymentId" }
        val response = paymentApiClient.reserve(paymentId)
        logger.info { "Reserve request accepted for payment $paymentId — HTTP ${response.status}" }
    }

    override fun sendFraudCheck(command: FraudCheckCommand) {
        logger.info { "Sending fraud check for payment ${command.paymentId}" }
        val json = objectMapper.writeValueAsString(command)
        meterRegistry.timer("fraud.smallrye.send.duration").recordCallable {
            fraudCommandEmitter.sendAndAwait(json)
        }
    }

    /**
     * Calls the SEPA transfer endpoint synchronously.
     *  - 4XX  → non-retryable ApplicationFailure, workflow moves to failure path
     *  - 5XX  → re-throw, Temporal retries per the default retry policy
     */
    override fun transfer(paymentId: String): TransferResponse {
        logger.info { "Executing SEPA transfer for payment $paymentId" }
        return try {
            paymentApiClient.sepaTransfer(paymentId)
        } catch (e: WebApplicationException) {
            val status = e.response.status
            if (status in 400..499) {
                throw ApplicationFailure.newNonRetryableFailure(
                    "SEPA transfer client error $status for payment $paymentId",
                    "ClientError",
                )
            }
            throw e
        }
    }

    override fun publishCompleted(paymentId: String) {
        logger.info { "Publishing payment-completed for payment $paymentId" }
        paymentApiClient.completed()
    }

    override fun publishRejected(paymentId: String, reason: String) {
        logger.info { "Publishing payment-rejected for payment $paymentId: $reason" }
        paymentApiClient.rejected(paymentId)
        meterRegistry.counter("payment.rejected", "reason", reason).increment()
    }
}
