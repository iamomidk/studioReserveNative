package com.studioreserve.bookings

import com.studioreserve.users.UserRole
import java.util.UUID

class BookingStatusService {
    private val allowedTransitions = mapOf(
        BookingStatus.PENDING to setOf(BookingStatus.ACCEPTED, BookingStatus.REJECTED, BookingStatus.CANCELLED),
        BookingStatus.ACCEPTED to setOf(BookingStatus.COMPLETED, BookingStatus.CANCELLED),
        BookingStatus.REJECTED to emptySet(),
        BookingStatus.COMPLETED to emptySet(),
        BookingStatus.CANCELLED to emptySet()
    )

    fun evaluate(
        role: UserRole,
        requesterId: UUID,
        context: BookingStatusContext,
        targetStatus: BookingStatus
    ): BookingStatusDecision {
        val allowedTargets = allowedTransitions[context.currentStatus].orEmpty()
        if (targetStatus !in allowedTargets) {
            return BookingStatusDecision.InvalidTransition
        }

        return when (role) {
            UserRole.ADMIN -> BookingStatusDecision.Allowed
            UserRole.STUDIO_OWNER -> {
                if (context.studioOwnerId == requesterId) BookingStatusDecision.Allowed else BookingStatusDecision.Forbidden
            }
            UserRole.PHOTOGRAPHER -> evaluatePhotographerDecision(requesterId, context, targetStatus)
        }
    }

    fun canTransition(
        role: UserRole,
        requesterId: UUID,
        context: BookingStatusContext,
        targetStatus: BookingStatus
    ): Boolean = evaluate(role, requesterId, context, targetStatus) is BookingStatusDecision.Allowed

    private fun evaluatePhotographerDecision(
        requesterId: UUID,
        context: BookingStatusContext,
        targetStatus: BookingStatus
    ): BookingStatusDecision {
        if (context.photographerId != requesterId) {
            return BookingStatusDecision.Forbidden
        }

        return if (targetStatus == BookingStatus.CANCELLED) {
            BookingStatusDecision.Allowed
        } else {
            BookingStatusDecision.Forbidden
        }
    }
}

data class BookingStatusContext(
    val bookingId: UUID,
    val currentStatus: BookingStatus,
    val photographerId: UUID,
    val studioOwnerId: UUID?
)

sealed interface BookingStatusDecision {
    data object Allowed : BookingStatusDecision
    data object Forbidden : BookingStatusDecision
    data object InvalidTransition : BookingStatusDecision
}
