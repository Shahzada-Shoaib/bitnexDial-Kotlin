package com.bitnextechnologies.bitnexdial.domain.usecase.call

import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import javax.inject.Inject

/**
 * Use case for ending active calls
 */
class EndCallUseCase @Inject constructor(
    private val sipRepository: ISipRepository,
    private val callRepository: ICallRepository
) {
    /**
     * End an active call
     * @param callId The ID of the call to end
     */
    suspend operator fun invoke(callId: String) {
        require(callId.isNotBlank()) { "Call ID cannot be empty" }
        sipRepository.endCall(callId)
    }
}
