package com.studioreserve.bookings

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.notifications.NotificationService
import com.studioreserve.payments.PaymentStatus
import com.studioreserve.studios.RoomsTable
import com.studioreserve.studios.StudiosTable
import com.studioreserve.users.UserRole
import com.studioreserve.users.UsersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.sql.FieldSet
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.orderBy
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

private val statusService = BookingStatusService()
private val pricingService = BookingPricingService
private val bookingLogger = LoggerFactory.getLogger("BookingRoutes")

fun Route.bookingRoutes(notificationService: NotificationService) {
    route("/api/bookings") {
        authenticate("auth-jwt") {
            post {
                val principal = call.principal<UserPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.PHOTOGRAPHER) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only photographers can create bookings"))
                }

                val photographerId = principal.userId.toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val request = runCatching { call.receive<CreateBookingRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                val normalizedEquipment = request.equipmentIds
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()

                val startInstant = BookingTimeUtils.parseInstantOrNull(request.startTime)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("startTime must be ISO-8601"))
                val endInstant = BookingTimeUtils.parseInstantOrNull(request.endTime)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("endTime must be ISO-8601"))

                if (!BookingTimeUtils.isChronologicallyValid(startInstant, endInstant)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("startTime must be before endTime"))
                }

                if (BookingTimeUtils.isStartTooFarInPast(startInstant, Instant.now())) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("startTime cannot be too far in the past")
                    )
                }

                val roomId = request.roomId.toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("roomId must be a valid UUID"))

                val startDateTime = BookingTimeUtils.toUtcLocalDateTime(startInstant)
                val endDateTime = BookingTimeUtils.toUtcLocalDateTime(endInstant)

                val creationResult = transaction {
                    val roomRow = RoomsTable.select { RoomsTable.id eq roomId }.singleOrNull()
                        ?: return@transaction BookingCreationResult.RoomNotFound

                    val overlapExists = BookingsTable.select {
                        (BookingsTable.roomId eq roomId) and
                            (BookingsTable.bookingStatus inList listOf(BookingStatus.PENDING, BookingStatus.ACCEPTED)) and
                            (BookingsTable.startTime less endDateTime) and
                            (BookingsTable.endTime greater startDateTime)
                    }.limit(1).any()

                    if (overlapExists) {
                        return@transaction BookingCreationResult.Conflict
                    }

                    val totalPrice = pricingService.calculateHourlyTotal(
                        hourlyPrice = roomRow[RoomsTable.hourlyPrice],
                        start = startDateTime,
                        end = endDateTime
                    )

                    val bookingId = UUID.randomUUID()
                    val equipmentPayload = serializeEquipmentIds(normalizedEquipment)

                    BookingsTable.insert { statement ->
                        statement[BookingsTable.id] = bookingId
                        statement[BookingsTable.roomId] = roomId
                        statement[BookingsTable.photographerId] = photographerId
                        statement[BookingsTable.startTime] = startDateTime
                        statement[BookingsTable.endTime] = endDateTime
                        statement[BookingsTable.equipmentIds] = equipmentPayload
                        statement[BookingsTable.totalPrice] = totalPrice
                        statement[BookingsTable.paymentStatus] = PaymentStatus.PENDING
                        statement[BookingsTable.bookingStatus] = BookingStatus.PENDING
                        statement[BookingsTable.createdAt] = LocalDateTime.now(ZoneOffset.UTC)
                    }

                    val inserted = BookingsTable.select { BookingsTable.id eq bookingId }.single()
                    BookingCreationResult.Success(inserted.toBookingDto())
                }

                when (creationResult) {
                    BookingCreationResult.Conflict -> call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("Room is already booked for the selected time range")
                    )
                    BookingCreationResult.RoomNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Room not found"))
                    is BookingCreationResult.Success -> call.respond(HttpStatusCode.Created, creationResult.booking)
                }
            }

            get {
                val principal = call.principal<UserPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                val userId = principal.userId.toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val statusFilter = call.request.queryParameters["status"]?.let { rawStatus ->
                    runCatching { BookingStatus.valueOf(rawStatus.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid status value"))
                    }
                }

                val fromDate = parseQueryDateTime(call.request.queryParameters["fromDate"], "fromDate") { message ->
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                }
                val toDate = parseQueryDateTime(call.request.queryParameters["toDate"], "toDate") { message ->
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                }

                if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromDate cannot be after toDate"))
                }

                val bookings = transaction {
                    val query = bookingsFieldSet().selectAll()
                    query.applyVisibilityFilter(role, userId)

                    statusFilter?.let { status ->
                        query.andWhere { BookingsTable.bookingStatus eq status }
                    }

                    fromDate?.let { start ->
                        query.andWhere { BookingsTable.startTime greaterEq start }
                    }

                    toDate?.let { end ->
                        query.andWhere { BookingsTable.endTime lessEq end }
                    }

                    query.orderBy(BookingsTable.startTime to SortOrder.ASC).map { it.toBookingDto() }
                }

                call.respond(HttpStatusCode.OK, bookings)
            }

            get("/{id}") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                val userId = principal.userId.toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val bookingIdParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Booking id is required"))
                val bookingId = bookingIdParam.toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid booking id"))

                val booking = transaction {
                    val query = bookingsFieldSet().select { BookingsTable.id eq bookingId }
                    query.applyVisibilityFilter(role, userId)
                    query.singleOrNull()?.toBookingDto()
                }

                if (booking == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Booking not found"))
                } else {
                    call.respond(HttpStatusCode.OK, booking)
                }
            }

            patch("/{id}/status") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                val userId = principal.userId.toUuidOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val bookingIdParam = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Booking id is required"))
                val bookingId = bookingIdParam.toUuidOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid booking id"))

                val request = runCatching { call.receive<UpdateBookingStatusRequest>() }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                val updateResult = transaction {
                    val bookingRow = bookingsFieldSet()
                        .select { BookingsTable.id eq bookingId }
                        .singleOrNull()
                        ?: return@transaction BookingStatusUpdateResult.NotFound

                    val context = BookingStatusContext(
                        bookingId = bookingRow[BookingsTable.id],
                        currentStatus = bookingRow[BookingsTable.bookingStatus],
                        photographerId = bookingRow[BookingsTable.photographerId],
                        studioOwnerId = bookingRow[StudiosTable.ownerId]
                    )

                    when (statusService.evaluate(role, userId, context, request.status)) {
                        BookingStatusDecision.Allowed -> {
                            BookingsTable.update({ BookingsTable.id eq bookingId }) { statement ->
                                statement[bookingStatus] = request.status
                            }

                            val refreshed = BookingsTable.select { BookingsTable.id eq bookingId }.single()
                            val phoneNumber = UsersTable
                                .select { UsersTable.id eq refreshed[BookingsTable.photographerId] }
                                .singleOrNull()
                                ?.get(UsersTable.phoneNumber)
                            BookingStatusUpdateResult.Success(refreshed.toBookingDto(), phoneNumber)
                        }
                        BookingStatusDecision.Forbidden -> BookingStatusUpdateResult.Forbidden
                        BookingStatusDecision.InvalidTransition -> BookingStatusUpdateResult.InvalidTransition
                    }
                }

                when (updateResult) {
                    BookingStatusUpdateResult.Forbidden -> call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("You are not allowed to update this booking")
                    )
                    BookingStatusUpdateResult.InvalidTransition -> call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid status transition")
                    )
                    BookingStatusUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Booking not found"))
                    is BookingStatusUpdateResult.Success -> {
                        call.respond(HttpStatusCode.OK, updateResult.booking)
                        val phoneNumber = updateResult.phoneNumber
                        if (phoneNumber == null) {
                            bookingLogger.warn(
                                "Skipping booking status SMS for booking {} because photographer phone number was not found",
                                updateResult.booking.id
                            )
                        } else {
                            try {
                                notificationService.sendBookingStatusSms(
                                    phoneNumber = phoneNumber,
                                    bookingId = updateResult.booking.id,
                                    status = updateResult.booking.bookingStatus
                                )
                            } catch (t: Throwable) {
                                bookingLogger.error(
                                    "Failed to send booking status SMS for booking {}",
                                    updateResult.booking.id,
                                    t
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseQueryDateTime(
    value: String?,
    fieldName: String,
    onError: (String) -> Nothing
): LocalDateTime? {
    value ?: return null
    val instant = BookingTimeUtils.parseInstantOrNull(value)
        ?: onError("$fieldName must be ISO-8601")
    return BookingTimeUtils.toUtcLocalDateTime(instant)
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun bookingsFieldSet(): FieldSet = BookingsTable
    .join(RoomsTable, JoinType.INNER, additionalConstraint = { BookingsTable.roomId eq RoomsTable.id })
    .join(StudiosTable, JoinType.INNER, additionalConstraint = { RoomsTable.studioId eq StudiosTable.id })
    .slice(BookingsTable.columns + listOf(StudiosTable.ownerId))

private fun Query.applyVisibilityFilter(role: UserRole, userId: UUID) {
    when (role) {
        UserRole.PHOTOGRAPHER -> andWhere { BookingsTable.photographerId eq userId }
        UserRole.STUDIO_OWNER -> andWhere { StudiosTable.ownerId eq userId }
        UserRole.ADMIN -> Unit
    }
}

private sealed interface BookingCreationResult {
    data object Conflict : BookingCreationResult
    data object RoomNotFound : BookingCreationResult
    data class Success(val booking: BookingDto) : BookingCreationResult
}

private sealed interface BookingStatusUpdateResult {
    data object Forbidden : BookingStatusUpdateResult
    data object InvalidTransition : BookingStatusUpdateResult
    data object NotFound : BookingStatusUpdateResult
    data class Success(val booking: BookingDto, val phoneNumber: String?) : BookingStatusUpdateResult
}
