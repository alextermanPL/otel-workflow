package com.luminor.workflow.testworkflow.model

import com.fasterxml.jackson.annotation.JsonProperty

data class ReservationResult(
    @JsonProperty("success") val success: Boolean,
    @JsonProperty("reason") val reason: String? = null,
)
