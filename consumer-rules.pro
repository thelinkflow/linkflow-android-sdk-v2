# Consumer ProGuard rules for LinkFlow Android SDK
# These rules are applied to apps that use this library.

# Keep LinkFlow SDK public API
-keep class com.linkflow.sdk.LinkFlowSDK { public *; }
-keep interface com.linkflow.sdk.LinkFlowSDK$* { *; }
-keep class com.linkflow.sdk.LinkFlowSDK$AttributionResult { *; }
-keep class com.linkflow.sdk.LinkFlowSDK$Reward { *; }
-keep class com.linkflow.sdk.LinkFlowSDK$RewardValidation { *; }
