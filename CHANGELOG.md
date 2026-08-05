# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-08-05

### Fixed
- **Deterministic attribution now works.** The SDK always read the Play Install
  Referrer and sent it as `installReferrer`, but the server had no field to bind
  it to and discarded it. Paired with the server fix that puts the click token in
  the Play Store URL's `referrer` parameter (the only one Play forwards), install
  referrer attribution resolves deterministically at full confidence.
- **Probabilistic attribution now works.** The device fingerprint hardcoded
  `ipAddress` to `"0.0.0.0"`, so it could never match a real click. The IP is now
  taken from the connection server-side and is no longer sent by the SDK.
- Attribution is retried until it succeeds. The first-launch flag was previously
  set before the network call, so a launch on a flaky connection lost the
  attribution permanently.
- Events are no longer dropped on network failure.

### Added
- `LinkFlowConfig` initializer carrying `appKey`, consent policy and retry
  settings. The legacy `initialize(context, apiBaseUrl, enableLogging)` still works.
- Durable offline event queue with exponential backoff and jitter. Each event
  carries a client-generated id the server uses as an idempotency key, so retries
  cannot double-count revenue.
- App key and install token authentication for the attribution endpoints.
- `setConsent()` for GDPR/DMA/CCPA gating. Launches requested before consent are
  buffered and replayed on grant rather than lost.
- Public `handleDeepLink(uri)` entry point (previously the React Native bridge
  had no way to feed a link into the SDK on Android).
- `getAttributionResult()`, `pendingEventCount()`.
- `AttributionResult` now reports `attributionMethod`, `confidence` and
  `isReinstall`.
- Device model and OS version in the fingerprint, which the server's scored
  matcher uses to corroborate an IP match.
- JVM unit tests.

### Changed
- Logging moved to `android.util.Log`; identifiers are redacted. The previous
  implementation logged full request bodies including the advertising ID.
- `sdkVersion` reported as `2.0.0` rather than a hardcoded `1.0.0`.
- Advertising ID collection can be disabled outright via `collectAdvertisingId`.

## [1.0.0] - 2024-11-01

### Added
- Initial standalone release extracted from main LinkFlow repository
- Deferred deep link resolution via Play Install Referrer API
- GAID (Google Advertising ID) collection with consent check
- Device fingerprinting for probabilistic attribution
- In-app event tracking with revenue support
- Reward validation and redemption (Phase 3)
- Kotlin coroutines for async operations
- ProGuard rules for release builds
- Maven publishing configuration
