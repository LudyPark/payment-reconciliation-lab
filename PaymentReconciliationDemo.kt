package dev.jaeeun.payments

import java.util.UUID

/**
 * A dependency-free Kotlin demonstration of an idempotent payment request whose
 * gateway response is uncertain.
 */
private enum class PaymentStatus { PROCESSING, CONFIRMED, FAILED }

private enum class GatewayResult { SUCCESS, FAILED, UNKNOWN }

private interface GatewayClient {
    fun authorize(externalReference: UUID, amount: Long): GatewayResult
    fun query(externalReference: UUID): GatewayResult
}

private class Payment(
    val idempotencyKey: String,
    val amount: Long,
    val id: UUID = UUID.randomUUID(),
    val externalReference: UUID = UUID.randomUUID(),
) {
    var status: PaymentStatus = PaymentStatus.PROCESSING
        private set

    fun reconcile(result: GatewayResult) {
        if (status != PaymentStatus.PROCESSING) return
        status = when (result) {
            GatewayResult.SUCCESS -> PaymentStatus.CONFIRMED
            GatewayResult.FAILED -> PaymentStatus.FAILED
            GatewayResult.UNKNOWN -> PaymentStatus.PROCESSING
        }
    }
}

private class InMemoryPaymentRepository {
    private val paymentsByKey = mutableMapOf<String, Payment>()
    private val paymentsById = mutableMapOf<UUID, Payment>()

    @Synchronized
    fun findOrCreate(idempotencyKey: String, amount: Long): CreateResult {
        paymentsByKey[idempotencyKey]?.let { return CreateResult(it, created = false) }

        val payment = Payment(idempotencyKey = idempotencyKey, amount = amount)
        paymentsByKey[idempotencyKey] = payment
        paymentsById[payment.id] = payment
        return CreateResult(payment, created = true)
    }

    @Synchronized
    fun findById(paymentId: UUID): Payment? = paymentsById[paymentId]

    data class CreateResult(val payment: Payment, val created: Boolean)
}

private class PaymentService(
    private val repository: InMemoryPaymentRepository,
    private val gateway: GatewayClient,
) {
    fun initiate(idempotencyKey: String, amount: Long): Payment {
        val result = repository.findOrCreate(idempotencyKey, amount)
        if (!result.created) return result.payment

        // A replay returns the existing record instead of authorizing again.
        result.payment.reconcile(gateway.authorize(result.payment.externalReference, result.payment.amount))
        return result.payment
    }

    fun reconcile(paymentId: UUID): Payment {
        val payment = requireNotNull(repository.findById(paymentId)) { "payment not found" }
        if (payment.status == PaymentStatus.PROCESSING) {
            payment.reconcile(gateway.query(payment.externalReference))
        }
        return payment
    }
}

fun main() {
    duplicateIdempotencyKeyReturnsTheOriginalPayment()
    unknownAuthorizationWaitsForReconciliation()
    reconciliationIsSafeToRunMoreThanOnce()
    println("All payment reconciliation checks passed.")
}

private fun duplicateIdempotencyKeyReturnsTheOriginalPayment() {
    val gateway = SimulatedGateway(authorizeResult = GatewayResult.SUCCESS)
    val service = PaymentService(InMemoryPaymentRepository(), gateway)
    val first = service.initiate("ride-101", 2_500)
    val replay = service.initiate("ride-101", 2_500)

    check(first.id == replay.id)
    check(gateway.authorizeCalls == 1)
    check(replay.status == PaymentStatus.CONFIRMED)
}

private fun unknownAuthorizationWaitsForReconciliation() {
    val gateway = SimulatedGateway(
        authorizeResult = GatewayResult.UNKNOWN,
        queryResult = GatewayResult.SUCCESS,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)
    val payment = service.initiate("ride-102", 3_000)

    check(payment.status == PaymentStatus.PROCESSING)
    check(service.reconcile(payment.id).status == PaymentStatus.CONFIRMED)
    check(gateway.queryCalls == 1)
}

private fun reconciliationIsSafeToRunMoreThanOnce() {
    val gateway = SimulatedGateway(
        authorizeResult = GatewayResult.UNKNOWN,
        queryResult = GatewayResult.FAILED,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)
    val payment = service.initiate("ride-103", 4_000)

    check(service.reconcile(payment.id).status == PaymentStatus.FAILED)
    check(service.reconcile(payment.id).status == PaymentStatus.FAILED)
    check(gateway.queryCalls == 1)
}

private class SimulatedGateway(
    private val authorizeResult: GatewayResult,
    private val queryResult: GatewayResult = authorizeResult,
) : GatewayClient {
    var authorizeCalls = 0
        private set
    var queryCalls = 0
        private set

    override fun authorize(externalReference: UUID, amount: Long): GatewayResult {
        authorizeCalls += 1
        return authorizeResult
    }

    override fun query(externalReference: UUID): GatewayResult {
        queryCalls += 1
        return queryResult
    }
}
