package com.plumery.workflow.testworkflow.workflow

import com.plumery.workflow.testworkflow.model.FraudCheckCommand
import com.plumery.workflow.testworkflow.model.TransferResponse
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface PaymentActivities {

    /** Sends a reservation request (fire & forget). Result arrives via signal. */
    @ActivityMethod
    fun reserveFunds(paymentId: String)

    /** Publishes a fraud check command to Kafka. Result arrives via onFraudResult signal. */
    @ActivityMethod
    fun sendFraudCheck(command: FraudCheckCommand)

    /** Calls the SEPA transfer endpoint synchronously. Retries on 5XX. */
    @ActivityMethod
    fun transfer(paymentId: String): TransferResponse

    /** Publishes a payment-completed event. */
    @ActivityMethod
    fun publishCompleted(paymentId: String)

    /** Publishes a payment-rejected event. */
    @ActivityMethod
    fun publishRejected(paymentId: String, reason: String)
}
