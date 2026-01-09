package com.bitnextechnologies.bitnexdial.domain.usecase.call

import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import javax.inject.Inject

/**
 * Use case for answering incoming calls
 */
class AnswerCallUseCase @Inject constructor(
    private val sipRepository: ISipRepository
) {
    /**
     * Answer an incoming call
     * @param callId The ID of the call to answer
     */
    suspend operator fun invoke(callId: String) {
        require(callId.isNotBlank()) { "Call ID cannot be empty" }
        sipRepository.answerCall(callId)
    }
}
