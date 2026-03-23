package com.luminor.workflow.testworkflow.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration

object ActivityStubFactory {

    fun preparePaymentActivities(): PaymentActivities =
        Workflow.newActivityStub(
            PaymentActivities::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build()
        )
}
