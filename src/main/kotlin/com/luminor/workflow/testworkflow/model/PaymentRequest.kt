package com.luminor.workflow.testworkflow.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class PaymentRequest(
    @JsonProperty("paymentId") val paymentId: String,
    @JsonProperty("amount") val amount: BigDecimal,
    @JsonProperty("currency") val currency: String,
    @JsonProperty("debtorAccount") val debtorAccount: String,
    @JsonProperty("creditorAccount") val creditorAccount: String,
)
