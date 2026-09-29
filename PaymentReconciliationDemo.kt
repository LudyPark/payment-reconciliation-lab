package dev.jaeeun.payments

import java.util.UUID

/**
 * Executable reference for a safe payment initiation flow.
 *
 * This keeps the state transitions and invariants visible without depending on
 * a framework or an actual provider. Run it with `bash run-demo.sh`.
 */

private enum class PaymentStatus {
    PROCESSING,
    CONFIRMED,
    FAILED,
}

private enum class ReconciliationStatus {
    NOT_REQUIRED,
    REQUIRED,
    RESOLVED,
}

private enum class GatewayDecision {
    APPROVED,
    DECLINED,
}

/**
 * `Unknown` means the provider may have processed the authorization, but this
 * service did not receive a final response. It is deliberately not a decline.
 */
private sealed interface AuthorizationResponse {
    data class Final(val decision: GatewayDecision) : AuthorizationResponse

    object Unknown : AuthorizationResponse
}

private class IdempotencyConflict(message: String) : IllegalArgumentException(message)

private data class PaymentRequest(
    val idempotencyKey: String,
    val amountMinor: Long,
    val currency: String,
    val customerReference: String,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "idempotency key is required" }
        require(amountMinor > 0) { "amount must be positive" }
        require(currency.length == 3) { "currency must be an ISO-4217 code" }
        require(customerReference.isNotBlank()) { "customer reference is required" }
    }

    /**
     * In production this could be a stable canonical JSON hash. Keeping the
     * fields explicit here makes the idempotency contract reviewable.
     */
    fun fingerprint(): String = listOf(amountMinor, currency.uppercase(), customerReference).joinToString("|")
}

private data class PaymentSnapshot(
    val paymentId: UUID,
    val externalReference: UUID,
    val status: PaymentStatus,
    val reconciliationStatus: ReconciliationStatus,
    val version: Long,
)

private interface PaymentGateway {
    fun authorize(externalReference: UUID, request: PaymentRequest): AuthorizationResponse

    /**
     * A null response means the provider has no final answer yet. It is not a
     * failed payment and must leave the local record pending.
     */
    fun query(externalReference: UUID): GatewayDecision?
}

/**
 * The aggregate owns every legal state transition. @Synchronized is enough
 * for this executable sample; a real multi-instance service needs a versioned
 * update such as `WHERE id = ? AND version = ? AND status = 'PROCESSING'`.
 */
private class PaymentIntent private constructor(
    private val request: PaymentRequest,
    val paymentId: UUID = UUID.randomUUID(),
    val externalReference: UUID = UUID.randomUUID(),
) {
    private var status: PaymentStatus = PaymentStatus.PROCESSING
    private var reconciliationStatus: ReconciliationStatus = ReconciliationStatus.NOT_REQUIRED
    private var version: Long = 0

    @Synchronized
    fun snapshot(): PaymentSnapshot = PaymentSnapshot(
        paymentId = paymentId,
        externalReference = externalReference,
        status = status,
        reconciliationStatus = reconciliationStatus,
        version = version,
    )

    @Synchronized
    fun hasSameIntent(candidate: PaymentRequest): Boolean = request.fingerprint() == candidate.fingerprint()

    /** Apply the first provider outcome exactly once after the durable intent exists. */
    @Synchronized
    fun recordInitialOutcome(response: AuthorizationResponse) {
        check(status == PaymentStatus.PROCESSING) { "initial outcome cannot be recorded after settlement" }

        when (response) {
            is AuthorizationResponse.Final -> moveToTerminal(response.decision)
            AuthorizationResponse.Unknown -> reconciliationStatus = ReconciliationStatus.REQUIRED
        }
        version += 1
    }

    @Synchronized
    fun needsReconciliation(): Boolean =
        status == PaymentStatus.PROCESSING && reconciliationStatus == ReconciliationStatus.REQUIRED

    /**
     * Only a provider query may settle an uncertain authorization. Calling this
     * repeatedly is safe: null preserves the pending record and terminal
     * records never move again.
     */
    @Synchronized
    fun reconcile(decision: GatewayDecision?): Boolean {
        if (!needsReconciliation() || decision == null) return false

        moveToTerminal(decision)
        reconciliationStatus = ReconciliationStatus.RESOLVED
        version += 1
        return true
    }

    private fun moveToTerminal(decision: GatewayDecision) {
        check(status == PaymentStatus.PROCESSING) { "a terminal payment cannot change state" }
        status = when (decision) {
            GatewayDecision.APPROVED -> PaymentStatus.CONFIRMED)
            GatewayDecision.DECLINED -> PaymentStatus.FAILED
        }
    }

    companion object {
        fun create(request: PaymentRequest): PaymentIntent = PaymentIntent(request = request)
    }
}

private interface PaymentRepository {
    fun createOrReplay(request: PaymentRequest): CreateOrReplay

    fun find(paymentId: UUID): PaymentIntent?
}

private sealed interface CreateOrReplay {
    data class Created(val payment: PaymentIntent) : CreateOrReplay

    data class Replay(val payment: PaymentIntent) : CreateOrReplay
}

private class InMemoryPaymentRepository : PaymentRepository {
    private val paymentsByKey = mutableMapOf<String, PaymentIntent>()
    private val paymentsById = mutableMapOf<UUID, PaymentIntent>()

    /**
     * This synchronized block represents a durable unique constraint on
     * idempotency_key. The intent is saved before any provider call happens.
     */
    @Synchronized
    override fun createOrReplay(request: PaymentRequest): CreateOrReplay {
        paymentsByKey[request.idempotencyKey]?.let { existing ->
            if (!existing.hasSameIntent(request)) {
                throw IdempotencyConflict(
                    "idempotency key cannot be reused for a different payment intent",
                )
            }
            return CreateOrReplay.Replay(existing)
        }

        val payment = PaymentIntent.create(request)
        paymentsByKey[request.idempotencyKey] = payment
        paymentsById[payment.paymentId] = payment
        return CreateOrReplay.Created(payment)
    }

    @Synchronized
    override fun find(paymentId: UUID): PaymentIntent? = paymentsById[pymentId]
}

private class PaymentService(
    private val repository: PaymentRepository,
    private val gateway: PaymentGateway,
) {
    fun initiate(request: PaymentRequest): PaymentSnapshot {
        return when (val result = repository.createOrReplay(request)) {
            is CreateOrReplay.Replay -> result.payment.snapshot()
            is CreateOrReplay.Created -> {
                // A retry never re-enters this branch, so authorization is sent once.
                val providerResponse = gateway.authorize(result.payment.externalReference, request)
                result.payment.recordInitialOutcome(providerResponse)
                result.payment.snapshot()
            }
        }
    }

    fun reconcile(paymentId: UUID): PaymentSnapshot {
        val payment = requireNotNull(repository.find(paymentId)) { "payment not found" }

        if (payment.needsReconciliation()) {
            // Reconciliation is query-only. It must never send another authorization.
            payment.reconcile(gateway.query(payment.externalReference))
        }
        return payment.snapshot()
    }
}

fun main() {
    aRepeatedRequestDoesNotAuthorizeTwice()
    aChangedIntentCannotReuseTheSameKey()
    aLostAuthorizationResponseSettlesThroughQuery()
    aPendingProviderAnswerStaysPendingUntilItCanBeResolved()
    aTerminalResultDoesNotRegressOrQueryAgain()

    println("All payment workflow checks passed.")
}

private fun aRepeatedRequestDoesNotAuthorizeTwice() {
    val gateway = SimulatedGateway(
        initialResponse = AuthorizationResponse.Final(GatewayDecision.APPROVED),
        ledgerDecision = GatewayDecision.APPROVED,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)
    val request = request(key = "payment-101")

    val first = service.initiate(request)
    val replay = service.initiate(request)

    check(first.paymentId == replay.paymentId)
    check(first.externalReference == replay.externalReference)
    check(replay.status == PaymentStatus.CONFIRMED)
    check(gateway.authorizeCalls == 1)
}

private fun aChangedIntentCannotReuseTheSameKey() {
    val gateway = SimulatedGateway(
        initialResponse = AuthorizationResponse.Final(GatewayDecision.APPROVED),
        ledgerDecision = GatewayDecision.APPROVED,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)

    service.initiate(request(key = "payment-conflict", amountMinor = 2_500))
    val conflict = runCatching {
        service.initiate(rest(key = "payment-conflict", amountMinor = 3_000))
    }.exceptionOrNull()

    check(conflict is IdempotencyConflict)
    check(gateway.authorizeCalls == 1)
}

private fun aLostAuthorizationResponseSettlesThroughQuery() {
    val gateway = SimulatedGateway(
        initialResponse = AuthorizationResponse.Unknown,
        ledgerDecision = GatewayDecision.APPROVED,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)

    val pending = service.initiate(request(key = "payment-lost-response"))
    check(pending.status == PaymentStatus.PROCESSING)
    check(pending.reconciliationStatus == ReconciliationStatus.REQUIRED)

    val settled = service.reconcile(pending.paymentId)
    check(settled.status == PaymentStatus.CONFIRMED)
    check(settled.reconciliationStatus == ReconciliationStatus.RESOLVED)
    check(gateway.authorizeCalls == 1)
    check(gateway.queryCalls == 1)
}

private fun aPendingProviderAnswerStaysPendingUntilItCanBeResolved() {
    val gateway = SimulatedGateway(
        initialResponse = AuthorizationResponse.Unknown,
        ledgerDecision = null,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)

    val pending = service.initiate(request(key = "payment-provider-pending"))
    val stillPending = service.reconcile(pending.paymentId)
    check(stillPending.status == PaymentStatus.PROCESSING)
    check(stillPending.reconciliationStatus == ReconciliationStatus.REQUIRED)

    gateway.resolveLater(GatewayDecision.DECLINED)
    val settled = service.reconcile(pending.paymentId)
    check(settled.status == PaymentStatus.FAILED)
    check(settled.reconciliationStatus == ReconciliationStatus.RESOLVED)
    check(gateway.authorizeCalls == 1)
    check(gateway.queryCalls == 2)
}

private fun aTerminalResultDoesNotRegressOrQueryAgain() {
    val gateway = SimulatedGateway(
        initialResponse = AuthorizationResponse.Unknown,
        ledgerDecision = GatewayDecision.APPROVED,
    )
    val service = PaymentService(InMemoryPaymentRepository(), gateway)

    val pending = service.initiate(request(key = "payment-terminal"))
    val settled = service.reconcile(pending.paymentId)
    val replayedReconciliation = service.reconcile(pending.paymentId)

    check(settled.status == PaymentStatus.CONFIRMED)
    check(replayedReconciliation == settled)
    check(gateway.queryCalls == 1)
}

private fun request(
    key: String,
    amountMinor: Long = 2_500,
): PaymentRequest = PaymentRequest(
    idempotencyKey = key,
    amountMinor = amountMinor,
    currency = "CAD",
    customerReference = "customer-42",
)

/**
 * The authorization response can be lost even when the provider ledger already
 * has a result. Querying the ledger later models an inquiry endpoint or a
 * webhook-backed reconciliation worker.
 */
private class SimulatedGateway(
    private val initialResponse: AuthorizationResponse,
    private var ledgerDecision: GatewayDecision?,
) : PaymentGateway {
    var authorizeCalls: Int = 0
        private set
    var queryCalls: Int = 0
        private set

    override fun authorize(externalReference: UUID, request: PaymentRequest): AuthorizationResponse {
        authorizeCalls += 1
        return initialResponse
    }

    override fun query(externalReference: UUID): GatewayDecision? {
        queryCalls += 1
        return ledgerDecision
    }

    fun resolveLater(decision: GatewayDecision) {
        ledgerDecision = decision
    }
}
