# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.2.0] - 2026-11-19

### Added
- `holdDeepLinks()` / `releaseDeepLinks()` / `isHoldingDeepLinks()`: hold
  deep-link delivery while the app cannot navigate (splash, onboarding,
  login). While held, the deferred deep link (an attributed result carrying a
  link) and direct deep links are queued, keeping only the latest of each kind,
  and delivered in arrival order on release. Not held by default.
- Organic installs: when the server returns `installId` / `installToken` for an
  unmatched install (`attributed: false`, server 2.2+), the SDK stores them, so
  organic users' events, revenue and identify calls are delivered. Previously
  these events stayed queued because there was no install id.
- `AttributionResult.match` (`AttributionMatch(method, confidence)`, nullable),
  read from the resolve response's optional `match` object.
- `getDiagnostics()` returning `LinkFlowDiagnostics`: SDK version, API host,
  consent state, whether an install id/token exist, last resolve time and
  result category, last match method, queued event count, hold state. It
  contains no identifiers or personal data.

### Fixed
- `deepLinkParams` / `campaignData` returned as a JSON *string* (how the server
  stores link params) are now decoded. Previously only the object form was
  read and the payload arrived empty. The iOS SDK already accepted both shapes.

### Compatibility
- **Fully backward compatible.** No public API was removed or renamed. New
  `AttributionResult.match` is the last constructor parameter, with a default
  value. Against servers that predate 2.2 (no organic install id, no `match`),
  behaviour is identical to 2.1.
- `sdkVersion` reported to the server is now `2.2.0`.

## [2.1.0] - 2026-10-14

### Added
- `setCustomerUserId(id)` / `getCustomerUserId()`: binds your own user id to the
  install (sent with the attribution request, every event, and via
  `/api/attribution/identify` after login; retried on the next launch if offline).
- Granular consent signals on `LinkFlowConsent`: `gdprApplies`, `tcfString`
  (IAB TCF v2), `adUserData` and `adPersonalization` (Google consent mode v2 /
  EU DMA). Forwarded with the attribution request when supplied.
- Events carry `eventTimestamp`, the time they happened on the device. Events
  queued offline were previously stamped with their delivery time.

### Compatibility
- **Fully backward compatible.** Every 2.0 call site compiles and behaves the
  same; all additions are optional. The SDK still works against servers that
  predate 2.1: the extra request fields are ignored, and a 404 from
  `/api/attribution/identify` stops the retry instead of looping.
- Older SDK builds keep working against the 2.1 server: it accepts the 1.x and
  2.0 payload shapes unchanged (pinned by server contract tests).

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
