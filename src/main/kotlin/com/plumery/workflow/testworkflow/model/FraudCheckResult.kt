package com.plumery.workflow.testworkflow.model

import com.fasterxml.jackson.annotation.JsonProperty

data class FraudCheckResult(
    @JsonProperty("workflowId") val workflowId: String,
    @JsonProperty("paymentId") val paymentId: String,
    @JsonProperty("status") val status: String,   // "CONTINUE" or "REJECT"
    @JsonProperty("reason") val reason: String? = null,
)
