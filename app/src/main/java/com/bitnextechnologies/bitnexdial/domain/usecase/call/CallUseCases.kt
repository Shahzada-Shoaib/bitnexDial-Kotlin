package com.bitnextechnologies.bitnexdial.domain.usecase.call

import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import javax.inject.Inject

/**
 * Container for all call-related use cases
 */
data class CallUseCases @Inject constructor(
    val makeCall: MakeCallUseCase,
    val answerCall: AnswerCallUseCase,
    val endCall: EndCallUseCase,
    val holdCall: HoldCallUseCase,
    val muteCall: MuteCallUseCase,
    val sendDtmf: SendDtmfUseCase,
    val transferCall: TransferCallUseCase,
    val getCallHistory: GetCallHistoryUseCase
)

/**
 * Use case for holding/resuming calls
 */
class HoldCallUseCase @Inject constructor(
    private val sipRepository: ISipRepository
) {
    suspend operator fun invoke(callId: String, hold: Boolean) {
        if (hold) {
            sipRepository.holdCall(callId)
        } else {
            sipRepository.resumeCall(callId)
        }
    }
}

/**
 * Use case for muting/unmuting calls
 */
class MuteCallUseCase @Inject constructor(
    private val sipRepository: ISipRepository
) {
    suspend operator fun invoke(callId: String, mute: Boolean) {
        sipRepository.muteCall(callId, mute)
    }
}

/**
 * Use case for sending DTMF tones
 */
class SendDtmfUseCase @Inject constructor(
    private val sipRepository: ISipRepository
) {
    suspend operator fun invoke(callId: String, digit: String) {
        require(digit.isNotEmpty() && digit.matches(Regex("[0-9*#]+"))) {
            "Invalid DTMF digit"
        }
        sipRepository.sendDtmf(callId, digit)
    }
}

/**
 * Use case for transferring calls
 */
class TransferCallUseCase @Inject constructor(
    private val sipRepository: ISipRepository
) {
    suspend operator fun invoke(callId: String, destination: String) {
        require(destination.isNotBlank()) {
            "Transfer destination cannot be empty"
        }
        sipRepository.transferCall(callId, destination)
    }
}

/**
 * Use case for getting call history
 */
class GetCallHistoryUseCase @Inject constructor(
    private val callRepository: ICallRepository
) {
    operator fun invoke() = callRepository.getCallHistory()

    fun asFlow() = callRepository.getCallHistory()
}
