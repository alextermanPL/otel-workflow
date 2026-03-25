package com.plumery.workflow.testworkflow.workflow

import com.plumery.workflow.testworkflow.model.PaymentRequest
import com.plumery.workflow.testworkflow.model.PaymentResult
import com.plumery.workflow.testworkflow.model.ReservationResult
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface PaymentWorkflow {

    @WorkflowMethod
    fun processPayment(request: PaymentRequest): PaymentResult

    /** External callback when the bank confirms (or rejects) the funds reservation. */
    @SignalMethod
    fun onReservationResult(result: ReservationResult)
}
