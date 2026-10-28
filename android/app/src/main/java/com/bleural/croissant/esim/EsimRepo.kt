package com.bleural.croissant.esim

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.bleural.croissant.tailscale.Root
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState
import net.typeblog.lpac_jni.impl.HttpInterfaceImpl
import net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl

/** One profile on the card, as the eSIM page shows it. Blank fields from the card are null. */
data class EsimProfile(
    val iccid: String,
    val nickname: String?,
    val profileName: String?,
    val provider: String?,
    val enabled: Boolean,
    val kind: Kind,
) {
    enum class Kind { Operational, Test, Provisioning }

    /** What to call it: the nickname if set, else the profile's name, else its provider, else the ICCID. */
    val name: String get() = nickname ?: profileName ?: provider ?: iccid
}

data class EsimCard(
    val eid: String,
    /** Free non-volatile memory in bytes; null if the card did not say. */
    val freeBytes: Int?,
    val firmware: String?,
    val profiles: List<EsimProfile>,
) {
    val inUse: EsimProfile? get() = profiles.firstOrNull { it.enabled }

    /** The card in a word or two: the profile in use, else whether there is any. */
    val summary: String get() = inUse?.name ?: if (profiles.isEmpty()) "No profile" else "None in use"
}

/** Why the card could not be read. */
enum class EsimProblem { NoSim, NotReady, NoRoot, NotEsim, NoAnswer }

/**
 * A write to the card: under way, or finished and
 * waiting for its page to be left. Adding walks [Step.Connecting] to [Step.Installing];
 * deleting and renaming are one step; putting a profile in use or out of it is
 * [Step.Switching] on the card and then [Step.Restarting], the SIM power cycle the
 * modem needs to read the card again, which is most of the minute it takes.
 */
data class EsimTask(
    val kind: Kind,
    /** The step reached; after a failure, the one that failed. */
    val step: Step,
    /** Adding: the server until the profile's name is known. Deleting: the profile's name. */
    val name: String,
    /** The profile: the one being deleted, or the one being added once the server has said. */
    val iccid: String? = null,
    /** Null while under way. */
    val outcome: Outcome? = null,
    /** Set when a session could not start or broke (no SIM, no root, no answer); [error] then carries only its raw words. */
    val problem: EsimProblem? = null,
    /** Why the operation failed, when the card or the SM-DP+ said. */
    val error: EsimError? = null,
    /** Tells one run from the next, so a Try again that ends the same way is still heard. */
    val started: Long = System.nanoTime(),
) {
    enum class Kind { Add, Delete, Enable, Disable, Rename }
    enum class Step { Connecting, Authenticating, Downloading, Installing, Deleting, Switching, Restarting, Renaming }
    enum class Outcome { Done, Failed, Cancelled }

    val running: Boolean get() = outcome == null

    /** lpac asks before every step up to installing whether to go on; loading into the card cannot be stopped. */
    val cancellable: Boolean get() = running && kind == Kind.Add && step != Step.Installing
}

data class EsimUi(
    /** A read is under way. */
    val reading: Boolean = false,
    /** The last good read, kept through a failed one so the page still shows what was there. */
    val card: EsimCard? = null,
    /** Why the last read failed; null after a good one. */
    val problem: EsimProblem? = null,
    /** The failure in its own words, shown under a problem the page cannot explain better. */
    val detail: String? = null,
    /** The network the modem is registered on; null without service. */
    val network: String? = null,
    /** The SIM's name for itself, which is the enabled profile's; null on the empty card's placeholder. */
    val simName: String? = null,
    /** SMS is off by the owner's restriction (Kiosk.apply), which the page shows as a toggle that cannot move. */
    val smsBlocked: Boolean = false,
    /** The last write to the card, until its page is left. */
    val task: EsimTask? = null,
    /** The add page's fields. */
    val draft: EsimDraft = EsimDraft(),
)

/**
 * The removable eUICC in the slot. Each read or write is one session:
 * start the root bridge, open lpac on it, work, close both. Only one session at a time,
 * on the IO pool: libeuicc is not thread-safe and the card holds one conversation. A
 * write ends by sending the card's pending notifications to their SM-DP+ and reading
 * the card again, in the same session. A switch is two: the card, then the SIM power
 * cycle once lpac has let go.
 */
class EsimRepo(private val context: Context) {

    companion object {
        private const val TAG = "Esim"
        private val ISD_R_AID = "A0000005591010FFFFFFFF8900000100".hexToBytes()
        /**
         * STORE DATA segment size. libeuicc's default is 60; 120 is lpac's and what step 1
         * downloaded with, and 255-byte APDUs pass the RIL intact.
         */
        private const val ES10X_MSS: Byte = 120

        /** The power cycle and what it is waited on for: the SIM took 50 s to come up on a real profile (step 1). */
        private const val REFRESH_MS = 6_000L
        private const val SIM_DOWN_MS = 15_000L
        private const val SIM_UP_MS = 90_000L
        private const val NETWORK_MS = 25_000L
        private const val POLL_MS = 500L

        /** The re-check: how long a slot that has just been powered up is given to present a card. */
        private const val RECHECK_MS = 30_000L
        /** How long absent must hold before it is believed: a card coming up can pass through it. */
        private const val ABSENT_MS = 3_000L
    }

    private class Failure(val problem: EsimProblem, message: String?) : Exception(message)

    private val cancel = AtomicBoolean(false)

    private val _state = MutableStateFlow(EsimUi())
    val state: StateFlow<EsimUi> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    init {
        _state.update { it.withPhone() }
    }

    /** Reads the card again; a read under way is left to finish, and a write reads it at its end anyway. */
    fun refresh() {
        if (_state.value.reading || _state.value.task?.running == true) return
        _state.update { it.copy(reading = true) }
        scope.launch {
            val result = lock.withLock { runCatching { checkSim(); withCard(::readCard) } }
            _state.update { s -> s.afterRead(result) }
        }
    }

    /** What a read leaves on the page: the card, or why there is none, over what was there before. */
    private fun EsimUi.afterRead(result: Result<EsimCard>) = result.fold(
        onSuccess = { copy(card = it, problem = null, detail = null) },
        onFailure = { e ->
            Log.w(TAG, "read failed", e)
            val (problem, detail) = problemOf(e)
            copy(problem = problem, detail = detail)
        },
    ).copy(reading = false).withPhone()

    /**
     * The eSIM page's Try again. A SIM that is ready is the whole answer and costs one
     * binder call, so that case is immediate. Anything else is not an answer at all: a
     * slot that has been powered down reads exactly like an empty one here, so the slot
     * is powered up, which does nothing to one that is already on, and looked at again.
     * Only a slot that will not present a card is No SIM.
     *
     * It is also the way back from a power cycle that broke with the slot off, which
     * nothing else in the app can reach: every other path runs [checkSim] first.
     */
    fun recheck() {
        if (_state.value.reading || _state.value.task?.running == true) return
        _state.update { it.copy(reading = true) }
        scope.launch {
            val result = lock.withLock {
                runCatching {
                    if (telephony.simState != TelephonyManager.SIM_STATE_READY) {
                        powerSlotOn()
                        waitForCard()
                    }
                    checkSim()
                    withCard(::readCard)
                }
            }
            _state.update { s -> s.afterRead(result) }
        }
    }

    fun editDraft(field: EsimDraft.Field, text: String) {
        _state.update { it.copy(draft = it.draft.with(field, text)) }
    }

    /** A link carrying an activation code (MainActivity) fills the add page. */
    fun fillDraft(code: ActivationCode) {
        _state.update { it.copy(draft = EsimDraft(code.address, code.matchingId.orEmpty())) }
    }

    /** Downloads the profile the draft names and installs it, disabled. */
    fun add() {
        val draft = _state.value.draft
        if (!draft.ready || _state.value.task?.running == true) return
        cancel.set(false)
        perform(EsimTask(EsimTask.Kind.Add, EsimTask.Step.Connecting, draft.address.trim())) {
            session { lpa -> download(lpa, draft) }
        }
    }

    /** Stops an add at lpac's next question; the session is cancelled on the SM-DP+ and the card. */
    fun cancelAdd() {
        cancel.set(true)
    }

    /** Deletes a disabled profile: the card refuses to delete the one in use. */
    fun delete(profile: EsimProfile) {
        if (_state.value.task?.running == true) return
        perform(EsimTask(EsimTask.Kind.Delete, EsimTask.Step.Deleting, profile.name, profile.iccid)) {
            session { lpa ->
                if (lpa.deleteProfile(profile.iccid)) finish(EsimTask.Outcome.Done)
                else finish(EsimTask.Outcome.Failed, error = EsimError("The card refused to delete the profile."))
            }
        }
    }

    /**
     * Puts [profile] in use, or takes it out of use; the card allows one at a time and
     * disables the other itself. Three parts, because a switch can take the session down
     * with it: the card alone, then the modem, then a session of its own to send the
     * notification and read the card back.
     *
     * The modem has to read the card again for the switch to mean anything. The card may
     * do that itself, by refreshing the SIM (it did on 2026-09-17, and the refresh is
     * what killed the session); when it has not stirred in [REFRESH_MS] the SIM is
     * powered down and up instead, which is what step 1 needed. Either way the task runs
     * until the modem is back: about a minute for a profile with a network to find, a few
     * seconds for the empty card's placeholder.
     */
    fun setEnabled(profile: EsimProfile, inUse: Boolean) {
        if (_state.value.task?.running == true) return
        val kind = if (inUse) EsimTask.Kind.Enable else EsimTask.Kind.Disable
        perform(EsimTask(kind, EsimTask.Step.Switching, profile.name, profile.iccid)) {
            checkSim()
            withCard { lpa ->
                val ok = if (inUse) lpa.enableProfile(profile.iccid) else lpa.disableProfile(profile.iccid)
                if (!ok) finish(
                    EsimTask.Outcome.Failed,
                    error = EsimError(
                        if (inUse) "The card refused to put the profile in use."
                        else "The card refused to take the profile out of use.",
                    ),
                )
            }
            // The card refused: there is nothing to restart for.
            if (_state.value.task?.running != true) return@perform
            step(EsimTask.Step.Restarting)
            if (!waitUntil(REFRESH_MS) { telephony.simState != TelephonyManager.SIM_STATE_READY }) powerCycle()
            val back = waitForSim(wantNetwork = inUse)
            // The card's own word on what happened, and the notification the switch queued.
            // A read that fails here leaves the page showing a card from before the switch,
            // so it says why and offers Try again rather than passing for the truth.
            runCatching { session() }.onFailure { e ->
                Log.w(TAG, "could not read the card after the switch", e)
                val (problem, detail) = problemOf(e)
                _state.update { it.copy(problem = problem, detail = detail) }
            }
            if (back) finish(EsimTask.Outcome.Done)
            else finish(
                EsimTask.Outcome.Failed,
                error = EsimError("The card switched, but the SIM did not come back. Restarting the device should bring it up."),
            )
        }
    }

    /** Renames [profile] on the card (its nickname, which is what the pages call it); blank clears it. */
    fun rename(profile: EsimProfile, nickname: String) {
        if (_state.value.task?.running == true) return
        perform(EsimTask(EsimTask.Kind.Rename, EsimTask.Step.Renaming, profile.name, profile.iccid)) {
            session { lpa ->
                try {
                    lpa.setNickname(profile.iccid, nickname.trim())
                    finish(EsimTask.Outcome.Done)
                } catch (e: Exception) {
                    Log.w(TAG, "rename failed", e)
                    finish(EsimTask.Outcome.Failed, error = EsimErrors.rename(e))
                }
            }
        }
    }

    /**
     * The mobile data switch, the eSIM page's other toggle. Nothing to do with the card:
     * `svc data` calls the framework as root, which is the only way in without being a
     * privileged or carrier app. The new state arrives back through
     * [com.bleural.croissant.DeviceStatusMonitor], which the modem tells directly.
     */
    fun setMobileData(on: Boolean) {
        scope.launch { Root.run("svc data ${if (on) "enable" else "disable"}") }
    }

    /**
     * Forgets a finished task once its page is left. [mine] is what that page was showing,
     * so a page leaving cannot take another's: the add page owns an Add, a profile page
     * owns whatever carries its ICCID.
     */
    fun dismissTask(mine: (EsimTask) -> Boolean) {
        _state.update { s -> if (s.task?.running == false && mine(s.task)) s.copy(task = null) else s }
    }

    /**
     * Runs one write to the card as the page's task, one at a time, with every failure on
     * the way turned into the task's problem. [body] leaves the outcome the page shows.
     */
    private fun perform(task: EsimTask, body: suspend () -> Unit) {
        _state.update { it.copy(task = task) }
        scope.launch {
            lock.withLock {
                runCatching { body() }.onFailure { e ->
                    Log.w(TAG, "${task.kind} failed", e)
                    val (problem, detail) = problemOf(e)
                    // A failure after the work itself finished is the read at the end of the
                    // session, not the work: the outcome stands, and the page says the card
                    // it is showing is out of date.
                    if (_state.value.task?.running == true) finish(EsimTask.Outcome.Failed, problem = problem, error = detail?.let { EsimError(it) })
                    else _state.update { it.copy(problem = problem, detail = detail) }
                }
            }
        }
    }

    /** One session for [work], then the card's pending notifications unless it failed, then the card into the page. */
    private fun session(work: (LocalProfileAssistant) -> Unit = {}) {
        checkSim()
        val card = withCard { lpa ->
            work(lpa)
            val outcome = _state.value.task?.outcome
            if (outcome != EsimTask.Outcome.Failed && outcome != EsimTask.Outcome.Cancelled) sendNotifications(lpa)
            readCard(lpa)
        }
        _state.update { it.copy(card = card, problem = null, detail = null).withPhone() }
    }

    /**
     * Powers the SIM down and up, which is how the modem is made to read the card again.
     * Its own short-lived bridge: lpac's session has to be closed before the SIM goes
     * down, or a channel it holds waits in the phone process.
     */
    private suspend fun powerCycle() {
        val bridge = Bridge.start(context.applicationInfo.sourceDir)
        val cycled = try {
            bridge.simPower(false)
            waitUntil(SIM_DOWN_MS) { telephony.simState != TelephonyManager.SIM_STATE_READY }
            bridge.simPower(true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "the power cycle broke", e)
            false
        } finally {
            bridge.close()
        }
        // The slot must never be left off. Nothing else in the app can reach a powered-down
        // card, since every path runs checkSim first, so a slot left off here would need a
        // reboot: one more try, on a bridge of its own. The switch then fails on the SIM not
        // coming back, which says to restart the device, and Try again can power it up too.
        if (!cycled) runCatching { powerSlotOn() }.onFailure { Log.w(TAG, "could not power the slot back up", it) }
    }

    /** Powers the slot up, on a bridge of its own. A slot that is already on does not notice. */
    private fun powerSlotOn() {
        val bridge = Bridge.start(context.applicationInfo.sourceDir)
        try {
            bridge.simPower(true)
        } finally {
            bridge.close()
        }
    }

    /**
     * Waits for a card after the slot has been powered up. Ready is the answer. Absent is
     * only the answer once it has held for [ABSENT_MS]: an empty slot stays there, while a
     * card on its way up can pass through it.
     */
    private suspend fun waitForCard(): Boolean {
        val deadline = SystemClock.uptimeMillis() + RECHECK_MS
        var absentSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val state = telephony.simState
            if (state == TelephonyManager.SIM_STATE_READY) return true
            if (state != TelephonyManager.SIM_STATE_ABSENT) absentSince = 0L
            else if (absentSince == 0L) absentSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - absentSince >= ABSENT_MS) return false
            delay(POLL_MS)
        }
        return telephony.simState == TelephonyManager.SIM_STATE_READY
    }

    /**
     * Waits for the modem after a power cycle: the SIM ready, and then, when a profile was
     * put in use, its network's name, which lands a few seconds later. Each look updates
     * the page, so the header fills in as it happens.
     */
    private suspend fun waitForSim(wantNetwork: Boolean): Boolean {
        val ready = waitUntil(SIM_UP_MS) { telephony.simState == TelephonyManager.SIM_STATE_READY }
        _state.update { it.withPhone() }
        if (!ready) return false
        if (wantNetwork) waitUntil(NETWORK_MS) {
            _state.update { it.withPhone() }
            _state.value.network != null
        }
        return true
    }

    /** Polls [test] every [POLL_MS] until it holds or [ms] has gone by; what it ended as. */
    private suspend fun waitUntil(ms: Long, test: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            if (test()) return true
            delay(POLL_MS)
        }
        return test()
    }

    private fun download(lpa: LocalProfileAssistant, draft: EsimDraft) {
        lpa.setEs10xMss(ES10X_MSS)
        val input = ProfileDownloadInput(draft.address.trim(), draft.matchingId.ifBlank { null }, null, draft.confirmation.ifBlank { null })
        try {
            lpa.downloadProfile(input) { state ->
                when (state) {
                    is ProfileDownloadState.Preparing, is ProfileDownloadState.Connecting -> step(EsimTask.Step.Connecting)
                    is ProfileDownloadState.Authenticating -> step(EsimTask.Step.Authenticating)
                    is ProfileDownloadState.ConfirmingDownload -> state.metadata?.let { m ->
                        val name = m.name.ifBlank { m.providerName }.ifBlank { null }
                        _state.update { s -> s.copy(task = s.task?.let { t -> t.copy(name = name ?: t.name, iccid = m.iccid.ifBlank { null }) }) }
                    }
                    is ProfileDownloadState.Downloading -> step(EsimTask.Step.Downloading)
                    is ProfileDownloadState.Finalizing -> step(EsimTask.Step.Installing)
                }
                !cancel.get()
            }
            finish(EsimTask.Outcome.Done)
            _state.update { it.copy(draft = EsimDraft()) }
        } catch (e: LocalProfileAssistant.ProfileDownloadException) {
            Log.w(TAG, "download failed: ${e.lpaErrorReason}", e.lastHttpException ?: e.lastApduException)
            if (cancel.get()) finish(EsimTask.Outcome.Cancelled)
            else finish(
                EsimTask.Outcome.Failed,
                error = EsimErrors.download(e.lpaErrorReason, e.lastHttpResponse?.data, e.lastHttpException, e.lastApduException),
            )
        }
    }

    private fun step(step: EsimTask.Step) {
        _state.update { it.copy(task = it.task?.copy(step = step)) }
    }

    private fun finish(outcome: EsimTask.Outcome, problem: EsimProblem? = null, error: EsimError? = null) {
        _state.update { it.copy(task = it.task?.copy(outcome = outcome, problem = problem, error = error)) }
    }

    /**
     * Sends what the card has queued for its SM-DP+ (install, enable, disable, delete) and
     * removes each one sent. Best effort: one that cannot be sent stays for the next write.
     */
    private fun sendNotifications(lpa: LocalProfileAssistant) {
        for (n in lpa.notifications.sortedBy { it.seqNumber }) {
            try {
                if (lpa.handleNotification(n.seqNumber)) lpa.deleteNotification(n.seqNumber)
                else Log.w(TAG, "notification ${n.seqNumber} not sent")
            } catch (e: Exception) {
                Log.w(TAG, "notification ${n.seqNumber} failed", e)
            }
        }
    }

    private fun checkSim() {
        when (telephony.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> throw Failure(EsimProblem.NoSim, null)
            // Powered down or starting: an open channel would wait in the phone process
            // instead of failing (verified 2026-09-17), so do not start a session at all.
            TelephonyManager.SIM_STATE_UNKNOWN, TelephonyManager.SIM_STATE_NOT_READY,
            TelephonyManager.SIM_STATE_CARD_IO_ERROR, TelephonyManager.SIM_STATE_CARD_RESTRICTED ->
                throw Failure(EsimProblem.NotReady, null)
        }
    }

    private fun readCard(lpa: LocalProfileAssistant): EsimCard {
        val info = lpa.euiccInfo2
        return EsimCard(
            eid = lpa.eID,
            freeBytes = info?.freeNvram,
            firmware = info?.euiccFirmwareVersion?.toString(),
            profiles = lpa.profiles.map(::profile),
        )
    }

    private fun problemOf(e: Throwable): Pair<EsimProblem, String?> = when (e) {
        is Failure -> e.problem to e.message
        is Bridge.NoRoot -> EsimProblem.NoRoot to e.message
        else -> EsimProblem.NoAnswer to (e.message ?: e.javaClass.simpleName)
    }

    private fun <T> withCard(work: (LocalProfileAssistant) -> T): T {
        val bridge = Bridge.start(context.applicationInfo.sourceDir)
        try {
            val lpa = try {
                LocalProfileAssistantImpl(ISD_R_AID, bridge, HttpInterfaceImpl())
            } catch (e: IllegalArgumentException) {
                // libeuicc could not open ISD-R. Status 3 is NO_SUCH_ELEMENT: the card has no
                // eUICC applet. Anything else is the path failing, not the card being wrong.
                val err = bridge.lastError
                throw if (err == "open status 3") Failure(EsimProblem.NotEsim, err)
                else Failure(EsimProblem.NoAnswer, err ?: e.message)
            }
            try {
                return work(lpa)
            } finally {
                // A switch can leave the channel dead, and closing then fails too: the
                // error that matters is the one from the work, not from letting go.
                runCatching { lpa.close() }
            }
        } finally {
            bridge.close()
        }
    }

    private fun profile(p: LocalProfileInfo) = EsimProfile(
        iccid = p.iccid,
        nickname = p.nickName.ifBlank { null },
        profileName = p.name.ifBlank { null },
        provider = p.providerName.ifBlank { null },
        enabled = p.state == LocalProfileInfo.State.Enabled,
        kind = when (p.profileClass) {
            ProfileClass.Testing -> EsimProfile.Kind.Test
            ProfileClass.Provisioning -> EsimProfile.Kind.Provisioning
            ProfileClass.Operational -> EsimProfile.Kind.Operational
        },
    )

    /** What the phone side knows without root: the network, the SIM's name, the SMS restriction. */
    private fun EsimUi.withPhone() = copy(
        network = telephony.networkOperatorName.takeIf { it.isNotBlank() && telephony.simState == TelephonyManager.SIM_STATE_READY },
        simName = telephony.simOperatorName.ifBlank { null },
        smsBlocked = context.getSystemService(UserManager::class.java).hasUserRestriction(UserManager.DISALLOW_SMS),
    )
}

/** The page's number formats, kept pure for the JVM tests. */
object EsimFormat {
    /** Bytes as whole kilobytes: 431434 is "421 KB". */
    fun bytes(n: Int): String = "${n / 1024} KB"

    /** A long number in groups of four, so an EID or ICCID can be read off the panel. */
    fun grouped(digits: String): String = digits.chunked(4).joinToString(" ")
}
