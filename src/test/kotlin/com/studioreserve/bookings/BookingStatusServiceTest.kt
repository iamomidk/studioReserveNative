package com.studioreserve.bookings

import com.studioreserve.users.UserRole
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookingStatusServiceTest {
    private val service = BookingStatusService()
    private val photographerId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val bookingId = UUID.randomUUID()

    private fun context(currentStatus: BookingStatus) = BookingStatusContext(
        bookingId = bookingId,
        currentStatus = currentStatus,
        photographerId = photographerId,
        studioOwnerId = ownerId
    )

    @Test
    fun `admin can transition along allowed path`() {
        val result = service.canTransition(
            role = UserRole.ADMIN,
            requesterId = UUID.randomUUID(),
            context = context(BookingStatus.PENDING),
            targetStatus = BookingStatus.ACCEPTED
        )

        assertTrue(result)
    }

    @Test
    fun `studio owner must own booking`() {
        val allowed = service.canTransition(
            role = UserRole.STUDIO_OWNER,
            requesterId = ownerId,
            context = context(BookingStatus.ACCEPTED),
            targetStatus = BookingStatus.COMPLETED
        )
        val forbidden = service.canTransition(
            role = UserRole.STUDIO_OWNER,
            requesterId = UUID.randomUUID(),
            context = context(BookingStatus.ACCEPTED),
            targetStatus = BookingStatus.COMPLETED
        )

        assertTrue(allowed)
        assertFalse(forbidden)
    }

    @Test
    fun `photographer can only cancel own booking`() {
        val allowed = service.canTransition(
            role = UserRole.PHOTOGRAPHER,
            requesterId = photographerId,
            context = context(BookingStatus.PENDING),
            targetStatus = BookingStatus.CANCELLED
        )
        val forbiddenDifferentUser = service.canTransition(
            role = UserRole.PHOTOGRAPHER,
            requesterId = UUID.randomUUID(),
            context = context(BookingStatus.PENDING),
            targetStatus = BookingStatus.CANCELLED
        )
        val forbiddenOtherStatus = service.canTransition(
            role = UserRole.PHOTOGRAPHER,
            requesterId = photographerId,
            context = context(BookingStatus.PENDING),
            targetStatus = BookingStatus.ACCEPTED
        )

        assertTrue(allowed)
        assertFalse(forbiddenDifferentUser)
        assertFalse(forbiddenOtherStatus)
    }

    @Test
    fun `invalid transition is rejected`() {
        val result = service.canTransition(
            role = UserRole.ADMIN,
            requesterId = UUID.randomUUID(),
            context = context(BookingStatus.COMPLETED),
            targetStatus = BookingStatus.CANCELLED
        )

        assertFalse(result)
    }
}
