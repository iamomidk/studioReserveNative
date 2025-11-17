package com.studioreserve.bookings

import com.studioreserve.users.UserRole
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingStatusServiceTest {
    private val service = BookingStatusService()
    private val photographerId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    private fun context(current: BookingStatus) = BookingStatusContext(
        bookingId = UUID.randomUUID(),
        currentStatus = current,
        photographerId = photographerId,
        studioOwnerId = ownerId,
    )

    @Test
    fun `studio owner can accept pending booking`() {
        val decision = service.evaluate(
            role = UserRole.STUDIO_OWNER,
            requesterId = ownerId,
            context = context(BookingStatus.PENDING),
            targetStatus = BookingStatus.ACCEPTED
        )

        assertTrue(decision is BookingStatusDecision.Allowed)
    }

    @Test
    fun `photographer can cancel own booking`() {
        val decision = service.evaluate(
            role = UserRole.PHOTOGRAPHER,
            requesterId = photographerId,
            context = context(BookingStatus.ACCEPTED),
            targetStatus = BookingStatus.CANCELLED
        )

        assertTrue(decision is BookingStatusDecision.Allowed)
    }

    @Test
    fun `photographer cannot accept booking`() {
        val decision = service.evaluate(
            role = UserRole.PHOTOGRAPHER,
            requesterId = photographerId,
            context = context(BookingStatus.PENDING),
            targetStatus = BookingStatus.ACCEPTED
        )

        assertTrue(decision is BookingStatusDecision.Forbidden)
    }

    @Test
    fun `invalid transition is rejected`() {
        val decision = service.evaluate(
            role = UserRole.ADMIN,
            requesterId = UUID.randomUUID(),
            context = context(BookingStatus.COMPLETED),
            targetStatus = BookingStatus.ACCEPTED
        )

        assertTrue(decision is BookingStatusDecision.InvalidTransition)
    }

    @Test
    fun `admin can always transition along allowed edges`() {
        val pendingContext = context(BookingStatus.PENDING)

        assertTrue(service.canTransition(UserRole.ADMIN, UUID.randomUUID(), pendingContext, BookingStatus.ACCEPTED))
        assertTrue(service.canTransition(UserRole.ADMIN, UUID.randomUUID(), pendingContext, BookingStatus.REJECTED))
        assertFalse(service.canTransition(UserRole.ADMIN, UUID.randomUUID(), pendingContext, BookingStatus.PENDING))
    }
}
