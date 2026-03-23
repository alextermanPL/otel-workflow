package com.luminor.workflow.testworkflow.api

import com.luminor.workflow.testworkflow.model.PaymentRequest
import com.luminor.workflow.testworkflow.model.ReservationResult
import com.luminor.workflow.testworkflow.workflow.PaymentWorkflow
import com.luminor.workflow.testworkflow.workflow.WorkflowConstants
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/payments")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class PaymentResource(
    private val workflowClient: WorkflowClient,
) {

    /**
     * Start a new payment workflow.
     * Returns 202 Accepted with the workflow ID so the caller can track progress.
     */
    @POST
    fun initiatePayment(request: PaymentRequest): Response {
        val workflowId = WorkflowConstants.WORKFLOW_ID_PREFIX + request.paymentId

        val stub = workflowClient.newWorkflowStub(
            PaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(WorkflowConstants.TASK_QUEUE)
                .build()
        )

        // Start async — caller gets the workflow ID back immediately
        WorkflowClient.start(stub::processPayment, request)

        return Response.accepted(mapOf("workflowId" to workflowId)).build()
    }

    /**
     * Bank callback — signals the running workflow with the reservation outcome.
     * Called by the bank (or a stub) after processing the reserve request.
     */
    @POST
    @Path("/{paymentId}/reservation-result")
    fun handleReservationResult(
        @PathParam("paymentId") paymentId: String,
        result: ReservationResult,
    ): Response {
        val stub = workflowClient.newWorkflowStub(
            PaymentWorkflow::class.java,
            WorkflowConstants.WORKFLOW_ID_PREFIX + paymentId,
        )
        stub.onReservationResult(result)
        return Response.ok(mapOf("signaled" to true)).build()
    }
}
