package com.luminor.workflow.testworkflow.model

import com.fasterxml.jackson.annotation.JsonProperty

data class PaymentResult(
    @JsonProperty("paymentId") val paymentId: String,
    @JsonProperty("status") val status: PaymentStatus,
    @JsonProperty("message") val message: String? = null,
)

enum class PaymentStatus { COMPLETED, FAILED, REJECTED }
