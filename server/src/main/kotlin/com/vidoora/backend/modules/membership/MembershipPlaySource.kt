package com.vidoora.backend.modules.membership

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.util.UUID

/** No cached journal state is trusted. Every cache refresh asks Google for the current receipt state. */
internal class MembershipPlaySource(private val verifier: PlayPurchaseVerifier, private val ledger: PlayPurchaseLedger,
    private val clock: Clock = Clock.systemUTC()) : VerifiedGrantSource {
    private val requests = Semaphore(4)
    override suspend fun current(account: UUID): List<VerifiedViewerGrant> = withTimeoutOrNull(25_000) {
        coroutineScope {
            ledger.tokens(account).map { token -> async {
                requests.withPermit {
                    try { verifier.verify(account, token, clock.instant()).grant() }
                    catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
                    catch (e: CancellationException) { throw e }
                    catch (_: PlayVerificationFailure) { null }
                }
            } }.awaitAll().filterNotNull()
        }
    } ?: emptyList()
}
