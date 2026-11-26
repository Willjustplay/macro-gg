package com.example.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Google Play Console configuration for Wjust macro PRO subscriptions.
 *
 * Setup in Google Play Console -> Monetize -> Products -> Subscriptions:
 * - Subscription Product ID : [SUBSCRIPTION_PRODUCT_ID] ("wjust_macro_premium")
 * - Monthly Base Plan ID    : [BASE_PLAN_MONTHLY] ("monthly")
 * - Yearly Base Plan ID     : [BASE_PLAN_YEARLY] ("yearly")
 */
object BillingConfig {
    const val SUBSCRIPTION_PRODUCT_ID = "wjust_macro_premium"
    const val BASE_PLAN_MONTHLY = "monthly"
    const val BASE_PLAN_YEARLY = "yearly"
    const val PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"
}

enum class SubscriptionPlan(
    val basePlanId: String,
    val title: String,
    val periodLabel: String,
    val badge: String?
) {
    MONTHLY(
        basePlanId = BillingConfig.BASE_PLAN_MONTHLY,
        title = "Bulanan",
        periodLabel = "/ bulan",
        badge = null
    ),
    YEARLY(
        basePlanId = BillingConfig.BASE_PLAN_YEARLY,
        title = "Tahunan",
        periodLabel = "/ tahun",
        badge = "HEMAT & POPULER"
    )
}

data class SubscriptionOfferUi(
    val plan: SubscriptionPlan,
    val offerToken: String,
    val formattedPrice: String,
    val introOfferLabel: String? = null,
    val isStoreReady: Boolean = false
)

data class BillingUiState(
    val isPremium: Boolean = false,
    val activePlanLabel: String = "Gratis",
    val monthlyOffer: SubscriptionOfferUi? = null,
    val yearlyOffer: SubscriptionOfferUi? = null,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val debugOverride: Boolean? = null
)

/**
 * Official Google Play Billing Library v7 manager for Monthly & Yearly PRO subscriptions.
 *
 * - Queries [BillingConfig.SUBSCRIPTION_PRODUCT_ID] offers for [BillingConfig.BASE_PLAN_MONTHLY]
 *   and [BillingConfig.BASE_PLAN_YEARLY].
 * - Automatically acknowledges active purchases (mandatory within 3 days on Google Play).
 * - Maintains an integrity-sealed offline grace cache (7 days) so paid users aren't locked out
 *   when temporarily offline, and refreshes live from Google Play whenever connectivity returns.
 */
class PlayBillingManager private constructor(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    @Volatile
    private var cachedProductDetails: ProductDetails? = null

    @Volatile
    private var lastVerifiedStorePremium: Boolean = false

    @Volatile
    private var lastVerifiedPlanLabel: String = "Gratis"

    @Volatile
    private var reconnectAttempt: Int = 0

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    init {
        val cachedValid = readValidCachedEntitlement()
        lastVerifiedStorePremium = cachedValid
        if (cachedValid) {
            lastVerifiedPlanLabel = prefs.getString(KEY_PLAN_LABEL, "PRO Aktif") ?: "PRO Aktif"
        }
        applyEffectiveEntitlement()
        startConnection()
    }

    fun startConnection() {
        if (billingClient.isReady) {
            queryCatalogAndPurchases()
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        runCatching {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        reconnectAttempt = 0
                        _uiState.update { it.copy(isConnected = true, isLoading = false) }
                        queryCatalogAndPurchases()
                    } else {
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isLoading = false,
                                statusMessage = null
                            )
                        }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    _uiState.update { it.copy(isConnected = false) }
                    scheduleReconnect()
                }
            })
        }.onFailure {
            _uiState.update { it.copy(isConnected = false, isLoading = false) }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= 4) return
        reconnectAttempt++
        val delayMs = (1500L * reconnectAttempt).coerceAtMost(8000L)
        scope.launch {
            delay(delayMs)
            startConnection()
        }
    }

    fun refreshEntitlements() {
        if (!billingClient.isReady) {
            startConnection()
        } else {
            queryCatalogAndPurchases()
        }
    }

    private fun queryCatalogAndPurchases() {
        querySubscriptionCatalog()
        queryActiveSubscriptions(showFeedback = false)
    }

    private fun querySubscriptionCatalog() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BillingConfig.SUBSCRIPTION_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val details = productDetailsList.firstOrNull { it.productId == BillingConfig.SUBSCRIPTION_PRODUCT_ID }
            cachedProductDetails = details
            val offers = details?.subscriptionOfferDetails.orEmpty()

            val monthly = pickBestOffer(offers, SubscriptionPlan.MONTHLY)
            val yearly = pickBestOffer(offers, SubscriptionPlan.YEARLY)
            _uiState.update {
                it.copy(
                    monthlyOffer = monthly,
                    yearlyOffer = yearly
                )
            }
        }
    }

    private fun pickBestOffer(
        offers: List<ProductDetails.SubscriptionOfferDetails>,
        plan: SubscriptionPlan
    ): SubscriptionOfferUi? {
        val matching = offers.filter { it.basePlanId == plan.basePlanId }
        if (matching.isEmpty()) return null

        // Prefer promotional/trial offer (has offerId) if eligible, otherwise base plan offer
        val chosen = matching.firstOrNull { !it.offerId.isNullOrBlank() } ?: matching.first()
        val phases = chosen.pricingPhases.pricingPhaseList
        val recurringPhase = phases.lastOrNull() ?: return null
        val introPhase = if (phases.size > 1) phases.first() else null

        val introLabel = introPhase?.let { phase ->
            when {
                phase.priceAmountMicros == 0L -> "Gratis coba dulu"
                else -> "Promo awal ${phase.formattedPrice}"
            }
        }
        return SubscriptionOfferUi(
            plan = plan,
            offerToken = chosen.offerToken,
            formattedPrice = recurringPhase.formattedPrice,
            introOfferLabel = introLabel,
            isStoreReady = true
        )
    }

    fun restorePurchases() {
        if (!billingClient.isReady) {
            _uiState.update {
                it.copy(statusMessage = "Menghubungkan ke Google Play...")
            }
            startConnection()
            return
        }
        _uiState.update { it.copy(isLoading = true, statusMessage = null) }
        queryActiveSubscriptions(showFeedback = true)
    }

    private fun queryActiveSubscriptions(showFeedback: Boolean) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = if (showFeedback) "Gagal memeriksa Google Play (${billingResult.debugMessage})." else it.statusMessage
                    )
                }
                return@queryPurchasesAsync
            }

            val activeSub = purchases.firstOrNull { p ->
                p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    p.products.contains(BillingConfig.SUBSCRIPTION_PRODUCT_ID)
            }

            if (activeSub != null) {
                if (!activeSub.isAcknowledged) {
                    acknowledgePurchase(activeSub)
                }
                val label = resolveActivePlanLabel(activeSub)
                lastVerifiedStorePremium = true
                lastVerifiedPlanLabel = label
                persistCachedEntitlement(active = true, token = activeSub.purchaseToken, planLabel = label)
                applyEffectiveEntitlement()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = if (showFeedback) "Langganan Wjust macro PRO berhasil dipulihkan!" else null
                    )
                }
            } else {
                lastVerifiedStorePremium = false
                lastVerifiedPlanLabel = "Gratis"
                persistCachedEntitlement(active = false, token = "", planLabel = "Gratis")
                applyEffectiveEntitlement()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = if (showFeedback) "Belum ada langganan PRO aktif di akun Google Play ini." else null
                    )
                }
            }
        }
    }

    fun launchSubscriptionPurchase(activity: Activity, plan: SubscriptionPlan) {
        if (!billingClient.isReady) {
            _uiState.update {
                it.copy(statusMessage = "Google Play Billing belum terhubung. Pastikan Google Play Store aktif.")
            }
            startConnection()
            return
        }

        val details = cachedProductDetails
        val offer = when (plan) {
            SubscriptionPlan.MONTHLY -> _uiState.value.monthlyOffer
            SubscriptionPlan.YEARLY -> _uiState.value.yearlyOffer
        }

        if (details == null || offer == null || offer.offerToken.isBlank()) {
            _uiState.update {
                it.copy(
                    statusMessage = "Paket '${plan.title}' belum tersedia di Google Play Console (Product ID: ${BillingConfig.SUBSCRIPTION_PRODUCT_ID}, Base Plan: ${plan.basePlanId})."
                )
            }
            querySubscriptionCatalog()
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _uiState.update {
                it.copy(statusMessage = "Tidak dapat membuka pembayaran Google Play: ${result.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val valid = purchases.orEmpty().firstOrNull { p ->
                    p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        p.products.contains(BillingConfig.SUBSCRIPTION_PRODUCT_ID)
                }
                if (valid != null) {
                    if (!valid.isAcknowledged) {
                        acknowledgePurchase(valid)
                    }
                    val label = resolveActivePlanLabel(valid)
                    lastVerifiedStorePremium = true
                    lastVerifiedPlanLabel = label
                    persistCachedEntitlement(active = true, token = valid.purchaseToken, planLabel = label)
                    applyEffectiveEntitlement()
                    _uiState.update {
                        it.copy(statusMessage = "Selamat! Wjust macro PRO kini aktif.")
                    }
                } else if (purchases.orEmpty().any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                    _uiState.update {
                        it.copy(statusMessage = "Pembayaran tertunda. PRO otomatis aktif setelah dikonfirmasi Google Play.")
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _uiState.update { it.copy(statusMessage = "Pembelian dibatalkan.") }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                queryActiveSubscriptions(showFeedback = true)
            }
            else -> {
                _uiState.update {
                    it.copy(statusMessage = "Google Play Billing: ${billingResult.debugMessage.ifBlank { "Error #${billingResult.responseCode}" }}")
                }
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { /* Google Play confirms entitlement */ }
    }

    private fun resolveActivePlanLabel(purchase: Purchase): String {
        val json = purchase.originalJson
        return when {
            json.contains(BillingConfig.BASE_PLAN_YEARLY, ignoreCase = true) -> "PRO Tahunan"
            json.contains(BillingConfig.BASE_PLAN_MONTHLY, ignoreCase = true) -> "PRO Bulanan"
            else -> "PRO Aktif"
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /**
     * DEBUG-ONLY preview toggle so developers can test Free vs PRO flows before publishing
     * subscription products in Google Play Console. Completely inert in Release builds.
     */
    fun setDebugPremiumOverride(enabled: Boolean?) {
        if (!BuildConfig.DEBUG) return
        _uiState.update { it.copy(debugOverride = enabled) }
        applyEffectiveEntitlement()
    }

    private fun applyEffectiveEntitlement() {
        val debugOverride = if (BuildConfig.DEBUG) _uiState.value.debugOverride else null
        val effectivePremium = debugOverride ?: lastVerifiedStorePremium
        val effectiveLabel = when {
            debugOverride == true -> "PRO (Mode Preview Debug)"
            debugOverride == false -> "Gratis (Mode Preview Debug)"
            lastVerifiedStorePremium -> lastVerifiedPlanLabel
            else -> "Gratis"
        }
        PremiumStateHolder.update(effectivePremium)
        _uiState.update {
            it.copy(
                isPremium = effectivePremium,
                activePlanLabel = effectiveLabel
            )
        }
    }

    // ---- Tamper-Resistant Offline Grace Cache (7 days) ----

    private fun persistCachedEntitlement(active: Boolean, token: String, planLabel: String) {
        val now = System.currentTimeMillis()
        val seal = computeSeal(active, token, now)
        prefs.edit()
            .putBoolean(KEY_ACTIVE, active)
            .putString(KEY_TOKEN_HASH, sha256(token))
            .putLong(KEY_VERIFIED_AT, now)
            .putString(KEY_PLAN_LABEL, planLabel)
            .putString(KEY_SEAL, seal)
            .apply()
    }

    private fun readValidCachedEntitlement(): Boolean {
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        if (!active) return false
        val verifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L)
        val now = System.currentTimeMillis()
        if (verifiedAt <= 0L || now < verifiedAt || now - verifiedAt > OFFLINE_GRACE_MS) {
            return false
        }
        val tokenHash = prefs.getString(KEY_TOKEN_HASH, "").orEmpty()
        val storedSeal = prefs.getString(KEY_SEAL, "").orEmpty()
        val expectedSeal = sha256("${appContext.packageName}|$active|$tokenHash|$verifiedAt|$SEAL_SALT")
        return storedSeal.isNotBlank() && storedSeal == expectedSeal
    }

    private fun computeSeal(active: Boolean, token: String, verifiedAt: Long): String {
        val tokenHash = sha256(token)
        return sha256("${appContext.packageName}|$active|$tokenHash|$verifiedAt|$SEAL_SALT")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "wjust_billing_cache_v1"
        private const val KEY_ACTIVE = "entitlement_active"
        private const val KEY_TOKEN_HASH = "entitlement_token_hash"
        private const val KEY_VERIFIED_AT = "entitlement_verified_at"
        private const val KEY_PLAN_LABEL = "entitlement_plan_label"
        private const val KEY_SEAL = "entitlement_seal"
        private const val SEAL_SALT = "wjustplay_macro_pro_seal_v1"
        private const val OFFLINE_GRACE_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

        @Volatile
        private var instance: PlayBillingManager? = null

        fun get(context: Context): PlayBillingManager =
            instance ?: synchronized(this) {
                instance ?: PlayBillingManager(context.applicationContext).also { instance = it }
            }
    }
}
