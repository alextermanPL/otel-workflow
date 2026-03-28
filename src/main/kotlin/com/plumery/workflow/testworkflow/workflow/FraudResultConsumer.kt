package com.plumery.workflow.testworkflow.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.plumery.workflow.testworkflow.model.FraudCheckResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.temporal.client.WorkflowClient
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming

private val logger = KotlinLogging.logger {}

@ApplicationScoped
class FraudResultConsumer(
    private val workflowClient: WorkflowClient,
    private val objectMapper: ObjectMapper,
) {

    @Incoming("fraud-check-results")
    fun onFraudResult(message: String) {
        val result = objectMapper.readValue(message, FraudCheckResult::class.java)
        logger.info { "Fraud result received: payment=${result.paymentId} status=${result.status}" }
        try {
            val stub = workflowClient.newWorkflowStub(PaymentWorkflow::class.java, result.workflowId)
            stub.onFraudResult(result)
        } catch (e: Exception) {
            // Workflow may have already completed (e.g. overall timeout fired before result arrived)
            logger.warn { "Could not signal workflow ${result.workflowId}: ${e.message}" }
        }
    }
}
