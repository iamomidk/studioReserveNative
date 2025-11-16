package com.studioreserve.bookings

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.payments.PaymentStatus
import com.studioreserve.studios.RoomsTable
import com.studioreserve.studios.StudiosTable
import com.studioreserve.users.UserRole
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
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

private const val PAST_THRESHOLD_MINUTES = 10L

fun Route.bookingRoutes() {
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

                val photographerId = runCatching { UUID.fromString(principal.userId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                }

                val request = runCatching { call.receive<CreateBookingRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                val normalizedEquipment = request.equipmentIds.distinct()
                val startInstant = request.startTime.parseInstantOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("startTime must be ISO-8601"))
                val endInstant = request.endTime.parseInstantOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("endTime must be ISO-8601"))

                if (!startInstant.isBefore(endInstant)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("startTime must be before endTime"))
                }

                val now = Instant.now()
                if (startInstant.isBefore(now.minus(Duration.ofMinutes(PAST_THRESHOLD_MINUTES)))) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("startTime cannot be too far in the past"))
                }

                val roomId = runCatching { UUID.fromString(request.roomId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("roomId must be a valid UUID"))
                }

                val startDateTime = LocalDateTime.ofInstant(startInstant, ZoneOffset.UTC)
                val endDateTime = LocalDateTime.ofInstant(endInstant, ZoneOffset.UTC)

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

                    val durationMinutes = Duration.between(startDateTime, endDateTime).toMinutes()
                    val billedHours = ((durationMinutes + 59) / 60).coerceAtLeast(1)
                    val hourlyPrice = roomRow[RoomsTable.hourlyPrice]
                    val totalPrice = BigDecimal.valueOf(hourlyPrice.toLong())
                        .multiply(BigDecimal.valueOf(billedHours.toLong()))
                        .setScale(2, RoundingMode.HALF_UP)

                    val bookingId = UUID.randomUUID()
                    val equipmentPayload = json.encodeToString(normalizedEquipment)

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
                    BookingCreationResult.Conflict -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Room is already booked for the selected time range"))
                    BookingCreationResult.RoomNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Room not found"))
                    is BookingCreationResult.Success -> call.respond(HttpStatusCode.Created, creationResult.booking)
                }
            }

            get {
                val principal = call.principal<UserPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                val userId = runCatching { UUID.fromString(principal.userId) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                }

                val statusFilter = call.request.queryParameters["status"]?.let { statusValue ->
                    runCatching { BookingStatus.valueOf(statusValue.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid status value"))
                    }
                }

                val fromDateParam = call.request.queryParameters["fromDate"]
                val fromDate = fromDateParam?.parseInstantOrNull()?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
                    ?: fromDateParam?.let {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromDate must be ISO-8601"))
                    }

                val toDateParam = call.request.queryParameters["toDate"]
                val toDate = toDateParam?.parseInstantOrNull()?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
                    ?: toDateParam?.let {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("toDate must be ISO-8601"))
                    }

                if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromDate cannot be after toDate"))
                }

                val bookings = transaction {
                    val query = BookingsTable
                        .join(RoomsTable, JoinType.INNER, additionalConstraint = { BookingsTable.roomId eq RoomsTable.id })
                        .join(StudiosTable, JoinType.INNER, additionalConstraint = { RoomsTable.studioId eq StudiosTable.id })
                        .slice(BookingsTable.columns)
                        .selectAll()

                    when (role) {
                        UserRole.PHOTOGRAPHER -> query.andWhere { BookingsTable.photographerId eq userId }
                        UserRole.STUDIO_OWNER -> query.andWhere { StudiosTable.ownerId eq userId }
                        UserRole.ADMIN -> Unit
                    }

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

            patch("/{id}/status") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.STUDIO_OWNER && role != UserRole.ADMIN) {
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("You are not allowed to update booking status"))
                }

                val userId = runCatching { UUID.fromString(principal.userId) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                }

                val bookingIdParam = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Booking id is required"))
                val bookingId = runCatching { UUID.fromString(bookingIdParam) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid booking id"))
                }

                val request = runCatching { call.receive<UpdateBookingStatusRequest>() }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                if (request.status == BookingStatus.PENDING) {
                    return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Cannot revert a booking to PENDING"))
                }

                val updateResult = transaction {
                    val bookingWithOwnership = BookingsTable
                        .join(RoomsTable, JoinType.INNER, additionalConstraint = { BookingsTable.roomId eq RoomsTable.id })
                        .join(StudiosTable, JoinType.INNER, additionalConstraint = { RoomsTable.studioId eq StudiosTable.id })
                        .select { BookingsTable.id eq bookingId }
                        .singleOrNull() ?: return@transaction BookingUpdateResult.NotFound

                    if (role == UserRole.STUDIO_OWNER && bookingWithOwnership[StudiosTable.ownerId] != userId) {
                        return@transaction BookingUpdateResult.Forbidden
                    }

                    val currentStatus = bookingWithOwnership[BookingsTable.bookingStatus]
                    if (!isValidTransition(currentStatus, request.status)) {
                        return@transaction BookingUpdateResult.InvalidTransition
                    }

                    BookingsTable.update({ BookingsTable.id eq bookingId }) { statement ->
                        statement[bookingStatus] = request.status
                    }

                    val refreshed = BookingsTable.select { BookingsTable.id eq bookingId }.single()
                    BookingUpdateResult.Success(refreshed.toBookingDto())
                }

                when (updateResult) {
                    BookingUpdateResult.Forbidden -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("You do not own this booking"))
                    BookingUpdateResult.InvalidTransition -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid status transition"))
                    BookingUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Booking not found"))
                    is BookingUpdateResult.Success -> call.respond(HttpStatusCode.OK, updateResult.booking)
                }
            }
        }
    }
}

@Serializable
data class CreateBookingRequest(
    val roomId: String,
    val startTime: String,
    val endTime: String,
    val equipmentIds: List<String> = emptyList()
)

@Serializable
data class BookingDto(
    val id: String,
    val roomId: String,
    val photographerId: String,
    val startTime: String,
    val endTime: String,
    val equipmentIds: List<String>,
    val totalPrice: Double,
    val paymentStatus: PaymentStatus,
    val bookingStatus: BookingStatus,
    val createdAt: String
)

@Serializable
data class UpdateBookingStatusRequest(val status: BookingStatus)

@Serializable
data class ErrorResponse(val message: String)

private fun ResultRow.toBookingDto(): BookingDto = BookingDto(
    id = this[BookingsTable.id].toString(),
    roomId = this[BookingsTable.roomId].toString(),
    photographerId = this[BookingsTable.photographerId].toString(),
    startTime = this[BookingsTable.startTime].toInstantString(),
    endTime = this[BookingsTable.endTime].toInstantString(),
    equipmentIds = parseEquipmentIds(this[BookingsTable.equipmentIds]),
    totalPrice = this[BookingsTable.totalPrice].toDouble(),
    paymentStatus = this[BookingsTable.paymentStatus],
    bookingStatus = this[BookingsTable.bookingStatus],
    createdAt = this[BookingsTable.createdAt].toInstantString()
)

private fun LocalDateTime.toInstantString(): String = this.atOffset(ZoneOffset.UTC).toInstant().toString()

private fun String.parseInstantOrNull(): Instant? = try {
    Instant.parse(this)
} catch (_: DateTimeParseException) {
    null
}

private fun parseEquipmentIds(raw: String): List<String> = runCatching {
    json.decodeFromString<List<String>>(raw)
}.getOrDefault(emptyList())

private sealed interface BookingCreationResult {
    data object Conflict : BookingCreationResult
    data object RoomNotFound : BookingCreationResult
    data class Success(val booking: BookingDto) : BookingCreationResult
}

private sealed interface BookingUpdateResult {
    data object Forbidden : BookingUpdateResult
    data object InvalidTransition : BookingUpdateResult
    data object NotFound : BookingUpdateResult
    data class Success(val booking: BookingDto) : BookingUpdateResult
}

private fun isValidTransition(current: BookingStatus, target: BookingStatus): Boolean = when (current) {
    BookingStatus.PENDING -> target in setOf(BookingStatus.ACCEPTED, BookingStatus.REJECTED, BookingStatus.CANCELLED)
    BookingStatus.ACCEPTED -> target in setOf(BookingStatus.COMPLETED, BookingStatus.CANCELLED)
    BookingStatus.REJECTED -> false
    BookingStatus.COMPLETED -> false
    BookingStatus.CANCELLED -> false
}

private val json = Json
