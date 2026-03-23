package com.luminor.workflow.testworkflow.model

import com.fasterxml.jackson.annotation.JsonProperty

data class TransferResponse(
    // "continue" means success; anything else is a failure
    @JsonProperty("status") val status: String,
)
