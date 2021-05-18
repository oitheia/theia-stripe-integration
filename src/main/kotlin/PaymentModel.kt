package br.com.theia.aldeia.domain.subscriptions

import java.time.LocalDateTime
import java.util.UUID

data class SubscriptionHistory(
    val id: String = UUID.randomUUID().toString(),
    val created: LocalDateTime = LocalDateTime.now(),
    val consultantId: String,
    val subscriptionId: String,
    val description: String? = null,
    val activityType: ActivityType
) {
    enum class ActivityType {
        AddedPaymentMethod,
        Activation,
        Cancellation,
    }
}

data class GatewayPlan(val id: String, val name: String, val amountInCents: Long)

data class GatewayProduct(val id: String, val name: String, val amountInCents: Long)

data class GatewayCoupon(
    val id: String,
    val name: String,
    val amountInCents: Long,
    val percentOff: Int,
    val products: List<String>? = listOf()
)

data class GatewayCustomer(val id: String, val paymentMethodId: String?)

data class GatewayPaymentInfos(
    val plans: List<GatewayPlan>,
    val products: List<GatewayProduct>,
    val trialDays: Long
)

data class GatewaySubscription(
    val id: String,
    val status: String,
    val currentPeriodEnd: Long,
    val planProductId: String? = null
)

data class GatewayPaymentIntent(
    val id: String,
    val status: String,
    val paymentMethodId: String? = null,
    val customerId: String? = null,
    val paymentIntentId: String? = null
) {
    fun succeeded(): Boolean {
        return status == "succeeded"
    }
}

data class GatewayOrder(
    val id: String,
    val status: String
)

data class GatewayCharge(
    val id: String,
    val status: String,
    val failureCode: String?,
    val failureMessage: String?
)

data class GatewayInvoice(
    val id: String,
    val status: String,
    val subscription: GatewaySubscription,
    val customerId: String? = null,
    val amountPaid: Long,
    val amountDue: Long,
    val charge: GatewayCharge?,
    val currentPeriodEnd: LocalDateTime?
) {
    fun succeeded(): Boolean {
        return status == "succeeded"
    }
}

data class GatewayCustomerPaymentInfo(
    val paymentMethodAdded: Boolean,
    val cardLastDigits: String? = null,
    val cardBrand: String? = null
)

data class GatewayPromotionCode(
    val code: String,
    val customerId: String?,
    val firstTimeTransaction: Boolean?,
    val coupon: GatewayCoupon
)
