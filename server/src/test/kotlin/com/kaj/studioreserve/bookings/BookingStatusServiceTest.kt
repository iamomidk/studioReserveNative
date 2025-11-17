package com.kaj.studioreserve.bookings

import com.kaj.studioreserve.users.UserRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.UUID

class BookingStatusServiceTest {
    private val service = BookingStatusService()
    private val bookingId = UUID.randomUUID()
    private val photographerId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    private val context = BookingStatusContext(
        bookingId = bookingId,
        currentStatus = BookingStatus.PENDING,
        photographerId = photographerId,
        studioOwnerId = ownerId
    )

    @Test
    fun `admin can accept or reject pending booking`() {
        assertTrue(service.canTransition(UserRole.ADMIN, UUID.randomUUID(), context, BookingStatus.ACCEPTED))
        assertTrue(service.canTransition(UserRole.ADMIN, UUID.randomUUID(), context, BookingStatus.REJECTED))
    }

    @Test
    fun `studio owner can transition only when owning booking`() {
        assertTrue(service.canTransition(UserRole.STUDIO_OWNER, ownerId, context, BookingStatus.ACCEPTED))
        assertFalse(service.canTransition(UserRole.STUDIO_OWNER, UUID.randomUUID(), context, BookingStatus.ACCEPTED))
    }

    @Test
    fun `photographer can only cancel their own pending booking`() {
        assertTrue(service.canTransition(UserRole.PHOTOGRAPHER, photographerId, context, BookingStatus.CANCELLED))
        assertFalse(service.canTransition(UserRole.PHOTOGRAPHER, photographerId, context, BookingStatus.ACCEPTED))
        assertFalse(service.canTransition(UserRole.PHOTOGRAPHER, UUID.randomUUID(), context, BookingStatus.CANCELLED))
    }

    @Test
    fun `invalid transition returns false`() {
        val acceptedContext = context.copy(currentStatus = BookingStatus.ACCEPTED)
        assertFalse(service.canTransition(UserRole.ADMIN, ownerId, acceptedContext, BookingStatus.REJECTED))
    }
}
