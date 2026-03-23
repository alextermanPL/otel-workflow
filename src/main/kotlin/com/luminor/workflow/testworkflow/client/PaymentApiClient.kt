package com.luminor.workflow.testworkflow.client

import com.luminor.workflow.testworkflow.model.TransferResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@RegisterRestClient(configKey = "payment-api")
interface PaymentApiClient {

    /** Fire & forget — bank accepts reservation asynchronously, result comes via callback. */
    @POST
    @Path("/api/payment/reserve/{paymentId}")
    fun reserve(@PathParam("paymentId") paymentId: String): Response

    /** SEPA transfer — synchronous call, retried by Temporal on 5XX. */
    @GET
    @Path("/api/payment/sepa/{paymentId}")
    fun sepaTransfer(@PathParam("paymentId") paymentId: String): TransferResponse

    /** Notify downstream that the payment completed. */
    @GET
    @Path("/api/payment/completed")
    fun completed(): Response

    /** Notify downstream that the payment was rejected. */
    @POST
    @Path("/api/payment/rejected/{paymentId}")
    fun rejected(@PathParam("paymentId") paymentId: String): Response
}
