package com.bitnextechnologies.bitnexdial.domain.usecase.call

import com.bitnextechnologies.bitnexdial.domain.repository.ISipRepository
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import javax.inject.Inject

/**
 * Use case for making outgoing calls
 */
class MakeCallUseCase @Inject constructor(
    private val sipRepository: ISipRepository
) {
    /**
     * Make a call to the specified phone number
     * @param phoneNumber The phone number to call
     * @return The call ID of the initiated call
     */
    suspend operator fun invoke(phoneNumber: String): String {
        // Validate phone number
        require(phoneNumber.isNotBlank()) { "Phone number cannot be empty" }

        // Check if SIP is registered
        check(sipRepository.isRegistered()) { "Not registered with phone system" }

        // Normalize phone number
        val normalizedNumber = PhoneNumberUtils.normalizeNumber(phoneNumber)

        // Make the call and return call ID
        return sipRepository.makeCall(normalizedNumber)
    }
}
