package com.plumery.workflow.testworkflow.workflow

import com.plumery.workflow.testworkflow.model.FraudCheckCommand
import com.plumery.workflow.testworkflow.model.FraudCheckResult
import com.plumery.workflow.testworkflow.model.PaymentRequest
import com.plumery.workflow.testworkflow.model.PaymentResult
import com.plumery.workflow.testworkflow.model.PaymentStatus
import com.plumery.workflow.testworkflow.model.ReservationResult
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
    private var fraudResult: FraudCheckResult? = null

    override fun onReservationResult(result: ReservationResult) {
        reservationResult = result
    }

    override fun onFraudResult(result: FraudCheckResult) {
        fraudResult = result
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

        // Block until the bank signals back (capped by the 10-min overall timeout)
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

        // ── Step 2: Fraud check (command via Kafka, result via signal) ────────
        logger.info { "Sending fraud check for payment ${request.paymentId}" }
        activities.sendFraudCheck(
            FraudCheckCommand(
                workflowId = Workflow.getInfo().workflowId,
                paymentId = request.paymentId,
                amount = request.amount,
                currency = request.currency,
                debtorAccount = request.debtorAccount,
                creditorAccount = request.creditorAccount,
            )
        )

        val fraudResultReceived = Workflow.await(Duration.ofMinutes(5)) { fraudResult != null }

        if (!fraudResultReceived) {
            logger.warn { "Fraud check timed out for payment ${request.paymentId}" }
            activities.publishRejected(request.paymentId, "Fraud check timeout")
            return PaymentResult(request.paymentId, PaymentStatus.REJECTED, "Fraud check timeout")
        }

        if (fraudResult?.status == "REJECT") {
            val reason = fraudResult?.reason ?: "Fraud detected"
            logger.warn { "Fraud check rejected payment ${request.paymentId}: $reason" }
            activities.publishRejected(request.paymentId, reason)
            return PaymentResult(request.paymentId, PaymentStatus.REJECTED, reason)
        }

        logger.info { "Fraud check passed for payment ${request.paymentId}" }

        // ── Step 3: Transfer (sync, retried by Temporal on 5XX) ──────────────
        logger.info { "Executing transfer for payment ${request.paymentId}" }
        val transferResponse = activities.transfer(request.paymentId)

        if (transferResponse.status != "continue") {
            logger.warn { "Transfer rejected for payment ${request.paymentId}: ${transferResponse.status}" }
            activities.publishRejected(request.paymentId, "Transfer failed: ${transferResponse.status}")
            return PaymentResult(request.paymentId, PaymentStatus.FAILED, "Transfer failed: ${transferResponse.status}")
        }

        // ── Step 4: Publish payment completed ────────────────────────────────
        logger.info { "Payment ${request.paymentId} completed successfully" }
        activities.publishCompleted(request.paymentId)
        return PaymentResult(request.paymentId, PaymentStatus.COMPLETED)
    }
}
