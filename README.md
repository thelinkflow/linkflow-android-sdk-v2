# LinkFlow Android SDK

Complete Android SDK for deferred deep linking, attribution, and rewards.

## Features

- ✅ Play Install Referrer integration
- ✅ GAID collection (respecting user consent)
- ✅ Deferred deep link retrieval
- ✅ Event tracking (install, in-app events)
- ✅ **Reward validation and redemption** (Phase 3)

## Installation

Add the SDK to your app's `build.gradle`:

```gradle
dependencies {
    implementation 'com.linkflow:android-sdk:1.0.0'
    implementation 'com.android.installreferrer:installreferrer:2.2'
    implementation 'com.google.android.gms:play-services-ads-identifier:18.0.1'
}
```

## Quick Start

### 1. Initialize SDK

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        LinkFlowSDK.initialize(
            context = this,
            apiBaseUrl = "https://thelinkflow.app",
            enableLogging = BuildConfig.DEBUG
        )
    }
}
```

### 2. Handle App Launch with Attribution

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sdk = LinkFlowSDK.getInstance()
        sdk.setAttributionCallback(object : LinkFlowSDK.AttributionCallback {
            override fun onAttributionResolved(result: LinkFlowSDK.AttributionResult) {
                if (result.attributed) {
                    // User came from a deep link
                    Log.d("LinkFlow", "Deep link: ${result.deepLinkValue}")
                    
                    // Check for rewards
                    result.rewards.forEach { reward ->
                        showRewardNotification(reward)
                    }
                }
            }
            
            override fun onAttributionError(error: Throwable) {
                Log.e("LinkFlow", "Attribution error", error)
            }
            
            override fun onDeepLinkReceived(uri: Uri) {
                // Handle deep link for existing users
                navigateToDeepLink(uri)
            }
        })
        
        sdk.handleAppLaunch(intent)
    }
}
```

## Reward Integration (Phase 3)

### 1. Display Available Rewards

```kotlin
fun showAvailableRewards() {
    val sdk = LinkFlowSDK.getInstance()
    val rewards = sdk.getAvailableRewards()
    
    rewards.forEach { reward ->
        when (reward.type) {
            "discount" -> {
                val percentage = reward.value["percentage"] as? Int
                showBanner("Get $percentage% off your first purchase!")
            }
            "credit" -> {
                val amount = reward.value["amount"] as? Double
                val currency = reward.value["currency"] as? String
                showBanner("You have $currency$amount in credits!")
            }
            "unlock" -> {
                val feature = reward.value["feature"] as? String
                showBanner("Premium feature unlocked: $feature")
            }
        }
    }
}
```

### 2. Validate Reward Before Use

```kotlin
fun validateReward(rewardId: String) {
    val sdk = LinkFlowSDK.getInstance()
    
    sdk.validateReward(rewardId) { validation ->
        if (validation?.valid == true) {
            // Show reward details to user
            AlertDialog.Builder(this)
                .setTitle(validation.reward?.title)
                .setMessage(validation.reward?.description)
                .setPositiveButton("Claim") { _, _ ->
                    claimReward(validation.redemptionToken!!)
                }
                .setNegativeButton("Later", null)
                .show()
        } else {
            // Show error
            val errors = validation?.errors?.joinToString(", ") ?: "Unknown error"
            Toast.makeText(this, "Reward unavailable: $errors", Toast.LENGTH_LONG).show()
        }
    }
}
```

### 3. Redeem Reward

```kotlin
fun claimReward(redemptionToken: String, purchaseAmount: Double? = null) {
    val sdk = LinkFlowSDK.getInstance()
    
    sdk.redeemReward(
        redemptionToken = redemptionToken,
        purchaseAmount = purchaseAmount,
        metadata = mapOf("user_id" to "123", "product_id" to "premium_monthly")
    ) { success, message ->
        if (success) {
            Toast.makeText(this, "Reward redeemed! $message", Toast.LENGTH_SHORT).show()
            // Apply reward to user account
            applyRewardToAccount()
        } else {
            Toast.makeText(this, "Failed: $message", Toast.LENGTH_LONG).show()
        }
    }
}
```

### Complete Example: Welcome Discount Flow

```kotlin
class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        
        val sdk = LinkFlowSDK.getInstance()
        val rewards = sdk.getAvailableRewards()
        
        // Find discount reward
        val discountReward = rewards.find { it.type == "discount" }
        
        if (discountReward != null) {
            // Validate reward
            sdk.validateReward(discountReward.id) { validation ->
                if (validation?.valid == true) {
                    showWelcomeScreen(validation)
                }
            }
        }
    }
    
    private fun showWelcomeScreen(validation: LinkFlowSDK.RewardValidation) {
        val percentage = validation.reward?.value?.get("percentage") as? Int ?: 0
        
        findViewById<TextView>(R.id.welcome_title).text = 
            "Welcome! Get $percentage% off \uD83C\uDF89"
        
        findViewById<Button>(R.id.claim_button).setOnClickListener {
            // User clicks "Shop Now" button
            navigateToShop(validation.redemptionToken!!)
        }
    }
    
    private fun navigateToShop(redemptionToken: String) {
        val intent = Intent(this, ShopActivity::class.java)
        intent.putExtra("redemption_token", redemptionToken)
        startActivity(intent)
    }
}

class ShopActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val redemptionToken = intent.getStringExtra("redemption_token")
        
        // When user completes purchase
        findViewById<Button>(R.id.checkout_button).setOnClickListener {
            val totalAmount = calculateTotal()
            
            if (redemptionToken != null) {
                // Redeem reward during checkout
                LinkFlowSDK.getInstance().redeemReward(
                    redemptionToken = redemptionToken,
                    purchaseAmount = totalAmount
                ) { success, _ ->
                    if (success) {
                        // Apply discount and complete purchase
                        applyDiscountAndCheckout()
                    }
                }
            }
        }
    }
}
```

## Event Tracking

```kotlin
// Track purchase event
sdk.trackEvent(
    eventName = "purchase",
    params = mapOf("product_id" to "premium_monthly"),
    revenue = 29.99
)

// Track custom events
sdk.trackEvent("level_completed", mapOf("level" to 5))
sdk.trackEvent("achievement_unlocked", mapOf("achievement_id" to "first_win"))
```

## Security Best Practices

1. **Device Fingerprinting**: The SDK automatically creates device fingerprints to prevent token sharing
2. **One-Time Tokens**: Redemption tokens can only be used once
3. **Expiration**: Rewards expire after configured days from install
4. **Server Validation**: All redemptions are validated server-side

## Troubleshooting

**No rewards showing?**
- Ensure the deep link has rewards configured in the dashboard
- Check that attribution was successful
- Verify reward hasn't expired

**Redemption failed?**
- Check minimum purchase amount requirements
- Ensure reward hasn't reached max redemptions
- Verify device fingerprint matches

## Support

For issues or questions, contact support@thelinkflow.app or visit our [documentation](https://docs.linkflow.io)
