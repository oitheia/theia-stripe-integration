import br.com.theia.aldeia.commons.datetime.toLocalDateTime
import br.com.theia.aldeia.commons.exceptions.CardDeclinedException
import br.com.theia.aldeia.domain.BookingPayment
import br.com.theia.aldeia.domain.Consultant
import br.com.theia.aldeia.domain.Id
import br.com.theia.aldeia.domain.PaymentGatewayRepository
import br.com.theia.aldeia.stripe.config.StripeConfiguration
import com.stripe.Stripe
import com.stripe.exception.CardException
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Charge
import com.stripe.model.Coupon
import com.stripe.model.Customer
import com.stripe.model.Invoice
import com.stripe.model.Order
import com.stripe.model.PaymentIntent
import com.stripe.model.PaymentMethod
import com.stripe.model.Plan
import com.stripe.model.Price
import com.stripe.model.Product
import com.stripe.model.PromotionCode
import com.stripe.model.Refund
import com.stripe.model.Sku
import com.stripe.model.Subscription
import com.stripe.net.Webhook
import com.stripe.param.OrderCreateParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.SkuCreateParams
import com.stripe.param.SubscriptionUpdateParams
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class StripeGatewayRepository {

    val prices: List<Price> by lazy {
        findAllPlansToCache()
    }

    init {
        Stripe.apiKey = "API+SECRET"
    }

    fun findAllPlans(): List<GatewayPlan> {
        val params = mapOf("limit" to 50, "product" to config.defaultPlanId())

        return Plan.list(params).data.map {
            GatewayPlan(
                it.id,
                it.nickname,
                it.amount
            )
        }
    }

    private fun findAllPlansToCache(): List<Price> {
        val params = mapOf("limit" to 50)

        return Price.list(params).data
    }

    fun findPlanById(planId: String): GatewayPlan? {
        val price = prices.find { it.product == planId }

        return if (price != null) {
            GatewayPlan(
                price.product,
                if (price.nickname != null) price.nickname else "",
                if (price.unitAmount != null) price.unitAmount else 0
            )
        } else null
    }

    fun retrieveInvoiceFromWebhookData(
        payload: String,
        sigHeader: String
    ): GatewayInvoice {
        val event = try {
            Webhook.constructEvent(
                payload, sigHeader, config.webhookInvoiceSignKey()
            )
        } catch (e: SignatureVerificationException) {
            throw IllegalStateException("Webhook data sign error")
        }

        val dataObjectDeserializer = event.dataObjectDeserializer
        val stripeObject = if (dataObjectDeserializer.getObject().isPresent) {
            dataObjectDeserializer.getObject().get()
        } else throw IllegalStateException("Error to parse Stripe event object")

        val invoice = stripeObject as Invoice

        val invoiceCharge = if (invoice.charge != null) {
            Charge.retrieve(invoice.charge)
        } else null

        val charge = if (invoiceCharge != null) {
            GatewayCharge(
                invoiceCharge.id,
                invoiceCharge.status,
                invoiceCharge.failureCode,
                invoiceCharge.failureMessage
            )
        } else null

        val subscription = Subscription.retrieve(invoice.subscription)

        val gatewaySubscription = GatewaySubscription(
            id = subscription.id,
            status = subscription.status,
            currentPeriodEnd = subscription.currentPeriodEnd,
            planProductId = subscription.items.data[0].plan.product
        )

        return GatewayInvoice(
            invoice.id,
            invoice.status,
            gatewaySubscription,
            invoice.customer,
            invoice.amountPaid,
            invoice.amountDue,
            charge,
            subscription.currentPeriodEnd?.toLocalDateTime()
        )
    }

    fun findPaymentIntentById(transactionCode: String): GatewayPaymentIntent? {
        val intentId = transactionCode.split("_secret_")[0]
        val intent = PaymentIntent.retrieve(intentId)

        return GatewayPaymentIntent(
            intent.clientSecret,
            intent.status
        )
    }

    fun createCustomer(consultant: Consultant, paymentMethodId: String?): GatewayCustomer {
        val params = if (paymentMethodId != null) {
            mapOf(
                "name" to consultant.user.name,
                "email" to consultant.user.emailToNotify(),
                "payment_method" to paymentMethodId,
                "invoice_settings" to mapOf(
                    "default_payment_method" to paymentMethodId
                )
            )
        } else {
            mapOf(
                "name" to consultant.user.name,
                "email" to consultant.user.emailToNotify()
            )
        }

        val customer = try {
            Customer.create(params)
        } catch (exception: CardException) {
            when (exception.code) {
                "card_declined" -> throw CardDeclinedException(exception.message ?: exception.code)
                else -> throw exception
            }
        }

        return GatewayCustomer(
            customer.id,
            customer.invoiceSettings.defaultPaymentMethod
        )
    }

    fun updateCustomer(
        customer: GatewayCustomer,
        paymentMethodId: String
    ): GatewayCustomer {
        val existentCustomer = Customer.retrieve(customer.id)
        if (customer.paymentMethodId != null) {
            val oldPaymentMethod = PaymentMethod.retrieve(customer.paymentMethodId)
            oldPaymentMethod.detach()
        }

        val pm: PaymentMethod = PaymentMethod.retrieve(paymentMethodId)
        pm.attach(PaymentMethodAttachParams.builder().setCustomer(customer.id).build())

        val params = mapOf(
            "invoice_settings" to mapOf(
                "default_payment_method" to paymentMethodId
            )
        )

        val newSCustomerState = existentCustomer.update(params)

        return GatewayCustomer(
            newSCustomerState.id,
            newSCustomerState.invoiceSettings.defaultPaymentMethod
        )
    }

    fun findAllProductsFromPaymentGateway(): List<GatewayProduct> {
        val params = mapOf(
            "limit" to 50,
            "product" to config.defaultProductId()
        )

        return Price.list(params).data.map {
            GatewayProduct(
                it.id,
                it.nickname ?: "",
                it.unitAmount
            )
        }
    }

    fun findProductById(productId: String): GatewayProduct {
        val params = mapOf(
            "limit" to 1,
            "product" to productId
        )
        val productName = Product.retrieve(productId).name
        return Price.list(params).data.map {
            GatewayProduct(
                it.id,
                productName,
                it.unitAmount
            )
        }.first()
    }

    fun createSubscription(
        customerId: String,
        planId: String,
        trialDaysRemaining: Long,
        paymentMethodId: String?,
        couponId: String?
    ): GatewaySubscription {

        val params = HashMap<String, Any?>()
        params["customer"] = customerId
        params["items"] = listOf(mapOf("plan" to planId))
        params["trial_period_days"] = trialDaysRemaining

        if (couponId != null) params["coupon"] = couponId

        if (paymentMethodId != null) params["default_payment_method"] = paymentMethodId

        val subscription = Subscription.create(params)

        return GatewaySubscription(
            subscription.id,
            subscription.status,
            subscription.currentPeriodEnd
        )
    }

    fun chargeWithPaymentIntent(
        amountInCents: Long,
        customerId: String,
        capture: Boolean?
    ): GatewayPaymentIntent {
        val canceledStatus = "canceled"
        val activeSubscription = Subscription
            .list(mapOf("customer" to customerId))
            .data
            .filter { it.status != canceledStatus }
            .minByOrNull { it.currentPeriodEnd }
        val defaultPaymentMethod = activeSubscription?.defaultPaymentMethod
        val paymentMethod = PaymentMethod.retrieve(defaultPaymentMethod)

        val paymentIntent = PaymentIntent.create(
            PaymentIntentCreateParams.builder()
                .setAmount(amountInCents)
                .setCurrency("brl")
                .setCustomer(paymentMethod.customer)
                .addPaymentMethodType("card")
                .setPaymentMethod(defaultPaymentMethod)
                .build()
        ).confirm()
        return GatewayPaymentIntent(
            id = paymentIntent.clientSecret,
            paymentIntentId = paymentIntent.id,
            status = paymentIntent.status,
            paymentMethodId = paymentIntent.paymentMethod,
            customerId = paymentIntent.customer
        )
    }

    fun chargeProduct(
        productId: Id,
        customerId: String,
        amountInCents: Long
    ): GatewayOrder {
        val canceledStatus = "canceled"
        val activeSubscription = Subscription
            .list(mapOf("" to ""))
            .data
            .filter { it.status != canceledStatus }
            .minByOrNull { it.currentPeriodEnd }
        val defaultPaymentMethod = activeSubscription?.defaultPaymentMethod

        val paymentMethod = PaymentMethod.retrieve(defaultPaymentMethod)
        if (paymentMethod.customer == null) {
            attachCustomerToPaymentMethod(customerId, paymentMethod)
        }

        val skuParams = SkuCreateParams.builder()
            .setCurrency("brl")
            .setInventory(
                SkuCreateParams.Inventory.builder()
                    .setType(SkuCreateParams.Inventory.Type.INFINITE)
                    .build()
            )
            .setPrice(amountInCents)
            .setProduct(productId)
            .build()
        val sku = Sku.create(skuParams)

        val params = OrderCreateParams.builder()
            .setCurrency("brl")
            .setCustomer(customerId)
            .addItem(
                OrderCreateParams.Item.builder()
                    .setType(OrderCreateParams.Item.Type.SKU)
                    .setParent(sku.id)
                    .setQuantity(1L)
                    .build()
            )
            .build()
        val order: Order = Order.create(params)

        order.id
        order.status

        return GatewayOrder(order.id, order.status)
    }

    private fun attachCustomerToPaymentMethod(customerId: String, paymentMethod: PaymentMethod) {
        val params = PaymentMethodAttachParams.builder()
            .setCustomer(customerId)
            .build()

        paymentMethod.attach(params)
    }

    fun findCustomerById(customerId: String): GatewayCustomer {
        val customer = Customer.retrieve(customerId)

        return GatewayCustomer(
            customer.id,
            customer.invoiceSettings.defaultPaymentMethod
        )
    }

    fun findAllSubscriptionsBy(
        customerId: String,
        planId: String
    ): List<GatewaySubscription> {
        val params = mapOf(
            "customer" to customerId,
            "plan" to planId
        )

        return Subscription
            .list(params)
            .data
            .map {
                GatewaySubscription(
                    it.id,
                    it.status,
                    it.currentPeriodEnd
                )
            }
    }

    fun findAllSubscriptionsBy(
        customerId: String
    ): List<GatewaySubscription> {
        val params = mapOf(
            "customer" to customerId
        )

        return Subscription
            .list(params)
            .data
            .map {
                GatewaySubscription(
                    it.id,
                    it.status,
                    it.currentPeriodEnd
                )
            }
    }

    fun cancelSubscription(subscription: GatewaySubscription): GatewaySubscription {
        val canceledSubscription = Subscription.retrieve(subscription.id).cancel()
        return GatewaySubscription(
            canceledSubscription.id,
            canceledSubscription.status,
            canceledSubscription.currentPeriodEnd
        )
    }

    fun chargeNowTrialSubscription(
        subscription: GatewaySubscription
    ): GatewaySubscription {
        val existentSubscription = Subscription.retrieve(subscription.id)

        val updatedSubscription = existentSubscription.update(
            SubscriptionUpdateParams
                .builder()
                .setTrialEnd(SubscriptionUpdateParams.TrialEnd.NOW)
                .build()
        )

        return GatewaySubscription(
            updatedSubscription.id,
            updatedSubscription.status,
            updatedSubscription.currentPeriodEnd
        )
    }

    fun findAllCoupons(): List<GatewayCoupon> {
        val params = mapOf(
            "limit" to 3
        )
        return Coupon
            .list(params)
            .data
            .map {
                GatewayCoupon(
                    it.id,
                    it.name,
                    if (it.amountOff != null) it.amountOff else 0,
                    if (it.percentOff != null) it.percentOff.intValueExact() else 0
                )
            }
    }

    fun deleteCustomerById(customerId: String): GatewayCustomer? {
        val customer = Customer.retrieve(customerId).delete()

        return GatewayCustomer(
            customer.id,
            ""
        )
    }

    fun findDefaultPaymentMethodBy(
        subscription: GatewaySubscription?
    ): GatewayCustomerPaymentInfo {
        val defaultPaymentMethodId = Subscription.retrieve(subscription?.id).defaultPaymentMethod
        return if (defaultPaymentMethodId != null) {
            GatewayCustomerPaymentInfo(
                paymentMethodAdded = true,
                PaymentMethod.retrieve(defaultPaymentMethodId).card?.last4,
                PaymentMethod.retrieve(defaultPaymentMethodId).card?.brand
            )
        } else {
            GatewayCustomerPaymentInfo(paymentMethodAdded = false)
        }
    }

    fun findPlanByProductId(stripeId: String): GatewayPlan? {
        val params = mapOf("limit" to 50, "product" to stripeId)

        return Plan.list(params).data.map {
            GatewayPlan(
                it.id,
                it.nickname,
                it.amount
            )
        }.first()
    }

    fun findPromotionCodeBy(code: String): GatewayPromotionCode? {
        val params = mapOf("code" to code)
        return PromotionCode.list(params).data.map { promotionCode ->
            val expandList = listOf("data.applies_to")
            val couponParams = mapOf("expand" to expandList)
            val coupon =
                Coupon.list(couponParams).data.first { it.id == promotionCode.coupon.id }
            GatewayPromotionCode(
                promotionCode.code,
                promotionCode.customer,
                promotionCode.restrictions.firstTimeTransaction,
                GatewayCoupon(
                    coupon.id,
                    coupon.name,
                    if (coupon.amountOff != null) coupon.amountOff else 0,
                    if (coupon.percentOff != null) coupon.percentOff.intValueExact() else 0,
                    coupon.appliesTo?.products
                )
            )
        }.firstOrNull()
    }

    fun cancelBookingPayment(bookingPayment: BookingPayment) {
        val paymentIntent = PaymentIntent.retrieve(bookingPayment.paymentIntentId!!)

        if (paymentIntent.status == "succeeded") {
            val params = mapOf(
                "payment_intent" to paymentIntent.id
            )
            Refund.create(params)
        } else paymentIntent.cancel()
    }

    fun updateSubscriptionPaymentMethod(
        subscriptionId: String,
        paymentMethodId: String
    ) {
        val subscription = Subscription.retrieve(subscriptionId)
        subscription.update(
            SubscriptionUpdateParams.builder().setDefaultPaymentMethod(paymentMethodId)
                .build()
        )
    }

    fun changeToBasicPlan(consultant: Consultant): GatewaySubscription? {
        val queryParams = mapOf(
            "customer" to consultant.customerId!!
        )

        val subscriptions = Subscription
            .list(queryParams)
            .data

        if (subscriptions.isEmpty()) return null

        val subscription = subscriptions.first()

        val params = SubscriptionUpdateParams.builder()
            .setCancelAtPeriodEnd(false)
            .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
            .addItem(
                SubscriptionUpdateParams.Item.builder()
                    .setId(subscription.items.data[0].id)
                    .setPrice("price_1HepolHVojryJN47sSL7kiN9")
                    .build()
            )
            .build()

        val updatedSubscription = subscription.update(params)

        return GatewaySubscription(
            updatedSubscription.id,
            updatedSubscription.status,
            updatedSubscription.currentPeriodEnd
        )
    }
}
