package com.luminor.workflow.testworkflow.workflow

import com.luminor.workflow.testworkflow.model.PaymentRequest
import com.luminor.workflow.testworkflow.model.PaymentResult
import com.luminor.workflow.testworkflow.model.PaymentStatus
import com.luminor.workflow.testworkflow.model.ReservationResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkiverse.temporal.TemporalWorkflow
import io.temporal.failure.CanceledFailure
import io.temporal.workflow.Workflow
import java.time.Duration

private val logger = KotlinLogging.logger {}

@TemporalWorkflow(workers = [WorkflowConstants.TASK_QUEUE])
class PaymentWorkflowImpl : PaymentWorkflow {

    private val activities = ActivityStubFactory.preparePaymentActivities()

    // ── Signal state ─────────────────────────────────────────────────────────

    private var reservationResult: ReservationResult? = null

    override fun onReservationResult(result: ReservationResult) {
        reservationResult = result
    }

    // ── Workflow entry point ──────────────────────────────────────────────────

    override fun processPayment(request: PaymentRequest): PaymentResult {
        var result: PaymentResult? = null

        // Overall timeout (timeout2) implemented as a cancellation scope + timer.
        // When the timer fires it cancels the scope, throwing CanceledFailure.
        val scope = Workflow.newCancellationScope(Runnable {
            result = executePaymentFlow(request)
        })

        Workflow.newTimer(Duration.ofMinutes(10)).thenApply {
            logger.warn { "Overall timeout reached for payment ${request.paymentId}" }
            scope.cancel()
        }

        return try {
            scope.run()
            result!!
        } catch (e: CanceledFailure) {
            // Run publishRejected in a detached scope so it is not itself cancelled
            Workflow.newDetachedCancellationScope(Runnable {
                activities.publishRejected(request.paymentId, "Overall timeout reached")
            }).run()
            PaymentResult(request.paymentId, PaymentStatus.REJECTED, "Overall timeout reached")
        }
    }


    private fun executePaymentFlow(request: PaymentRequest): PaymentResult {

        // ── Step 1: Reserve funds (fire & forget, wait for signal) ────────────
        logger.info { "Reserving funds for payment ${request.paymentId}" }
        activities.reserveFunds(request.paymentId)

        // Block until the bank signals back, or timer1 (2 min) expires
        val signalReceived = Workflow.await(Duration.ofMinutes(20)) { reservationResult != null }

        if (!signalReceived) {
            logger.warn { "Reservation signal timed out for payment ${request.paymentId}" }
            activities.publishRejected(request.paymentId, "Reservation timed out")
            return PaymentResult(request.paymentId, PaymentStatus.REJECTED, "Reservation timed out")
        }

        if (reservationResult?.success != true) {
            val reason = reservationResult?.reason ?: "Reservation rejected"
            logger.warn { "Reservation failed for payment ${request.paymentId}: $reason" }
            activities.publishRejected(request.paymentId, reason)
            return PaymentResult(request.paymentId, PaymentStatus.FAILED, reason)
        }

        // ── Step 2: Transfer (sync, retried by Temporal on 5XX) ──────────────
        logger.info { "Executing transfer for payment ${request.paymentId}" }
        val transferResponse = activities.transfer(request.paymentId)

        if (transferResponse.status != "continue") {
            logger.warn { "Transfer rejected for payment ${request.paymentId}: ${transferResponse.status}" }
            activities.publishRejected(request.paymentId, "Transfer failed: ${transferResponse.status}")
            return PaymentResult(request.paymentId, PaymentStatus.FAILED, "Transfer failed: ${transferResponse.status}")
        }

        // ── Step 3: Publish payment completed ────────────────────────────────
        logger.info { "Payment ${request.paymentId} completed successfully" }
        activities.publishCompleted(request.paymentId)
        return PaymentResult(request.paymentId, PaymentStatus.COMPLETED)
    }
}
