package com.studioreserve.bookings

import com.studioreserve.config.StudiosTable
import com.studioreserve.config.UsersTable
import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

fun Route.bookingRoutes() {
    route("/api/bookings") {
        post {
            val user = call.currentUserOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Missing authenticated user"))

            if (user.role != UserRole.PHOTOGRAPHER) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("Only photographers can create bookings"))
            }

            val request = call.receive<CreateBookingRequest>()
            val normalizedRequest = request.copy(equipmentIds = request.equipmentIds.distinct())

            val now = Clock.System.now()
            if (normalizedRequest.startTime >= normalizedRequest.endTime) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("startTime must be before endTime"))
            }

            if (normalizedRequest.startTime < now) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("startTime cannot be in the past"))
            }

            val bookingResponse = try {
                transaction {
                    val roomRow = RoomsTable.select { RoomsTable.id eq normalizedRequest.roomId }
                        .singleOrNull()
                        ?: throw ResourceNotFoundException("Room ${normalizedRequest.roomId} not found")

                    val studioId = roomRow[RoomsTable.studioId]
                    val hourlyRate = roomRow[RoomsTable.hourlyRate]

                    val overlapExists = BookingsTable.select {
                        (BookingsTable.roomId eq normalizedRequest.roomId) and
                                (BookingsTable.bookingStatus inList listOf(BookingStatus.PENDING, BookingStatus.ACCEPTED)) and
                                (BookingsTable.startTime less normalizedRequest.endTime) and
                                (BookingsTable.endTime greater normalizedRequest.startTime)
                    }.limit(1).any()

                    if (overlapExists) {
                        throw BookingConflictException("Room is already booked for the selected time range")
                    }

                    if (normalizedRequest.equipmentIds.isNotEmpty()) {
                        val equipmentRows = EquipmentTable.select { EquipmentTable.id inList normalizedRequest.equipmentIds }
                            .toList()

                        if (equipmentRows.size != normalizedRequest.equipmentIds.size) {
                            throw ResourceNotFoundException("One or more equipment items were not found")
                        }

                        equipmentRows.forEach { row ->
                            if (row[EquipmentTable.studioId] != studioId) {
                                throw BookingValidationException("Equipment ${row[EquipmentTable.id]} does not belong to the same studio as the room")
                            }

                            if (row[EquipmentTable.status] != EquipmentStatus.AVAILABLE) {
                                throw BookingConflictException("Equipment ${row[EquipmentTable.id]} is not available")
                            }
                        }
                    }

                    val totalPrice = calculateTotalPrice(hourlyRate, normalizedRequest.startTime, normalizedRequest.endTime)

                    val bookingId = BookingsTable.insert { statement ->
                        statement[roomId] = normalizedRequest.roomId
                        statement[userId] = user.id
                        statement[startTime] = normalizedRequest.startTime
                        statement[endTime] = normalizedRequest.endTime
                        statement[totalPriceColumn] = totalPrice
                        statement[bookingStatus] = BookingStatus.PENDING
                        statement[paymentStatus] = PaymentStatus.PENDING
                    } get BookingsTable.id

                    BookingResponse(
                        id = bookingId,
                        roomId = normalizedRequest.roomId,
                        userId = user.id,
                        startTime = normalizedRequest.startTime,
                        endTime = normalizedRequest.endTime,
                        equipmentIds = normalizedRequest.equipmentIds,
                        totalPrice = totalPrice.toDouble(),
                        bookingStatus = BookingStatus.PENDING,
                        paymentStatus = PaymentStatus.PENDING
                    )
                }
            } catch (conflict: BookingConflictException) {
                return@post call.respond(HttpStatusCode.Conflict, ApiError(conflict.message ?: "Booking conflict"))
            } catch (validation: BookingValidationException) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError(validation.message ?: "Invalid booking request"))
            } catch (notFound: ResourceNotFoundException) {
                return@post call.respond(HttpStatusCode.NotFound, ApiError(notFound.message ?: "Resource not found"))
            }

            call.respond(HttpStatusCode.Created, bookingResponse)
        }
    }
}

@Serializable
data class CreateBookingRequest(
    val roomId: Int,
    val startTime: Instant,
    val endTime: Instant,
    val equipmentIds: List<Int> = emptyList()
)

@Serializable
data class BookingResponse(
    val id: Int,
    val roomId: Int,
    val userId: Int,
    val startTime: Instant,
    val endTime: Instant,
    val equipmentIds: List<Int>,
    val totalPrice: Double,
    val bookingStatus: BookingStatus,
    val paymentStatus: PaymentStatus
)

@Serializable
data class ApiError(val message: String)

data class AuthenticatedUser(
    val id: Int,
    val role: UserRole
)

private val CurrentUserKey = AttributeKey<AuthenticatedUser>("CurrentUser")

fun ApplicationCall.currentUserOrNull(): AuthenticatedUser? {
    return when {
        attributes.contains(CurrentUserKey) -> attributes[CurrentUserKey]
        else -> null
    }
}

private fun calculateTotalPrice(hourlyRate: BigDecimal, start: Instant, end: Instant): BigDecimal {
    val duration = Duration.between(start.toJavaInstant(), end.toJavaInstant())
    val minutes = duration.toMinutes()
    val hoursDecimal = BigDecimal(minutes).divide(BigDecimal(60), 2, RoundingMode.HALF_UP)
    val billableHours = if (hoursDecimal > BigDecimal.ZERO) hoursDecimal else BigDecimal.ZERO
    return hourlyRate.multiply(billableHours)
}

enum class EquipmentStatus {
    AVAILABLE,
    UNAVAILABLE,
    RESERVED
}

enum class BookingStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED
}

enum class PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED
}

class BookingConflictException(message: String) : RuntimeException(message)
class BookingValidationException(message: String) : RuntimeException(message)
class ResourceNotFoundException(message: String) : RuntimeException(message)

object RoomsTable : Table("rooms") {
    val id: Column<Int> = integer("id").autoIncrement()
    val studioId: Column<Int> = integer("studio_id").references(StudiosTable.id)
    val name: Column<String> = varchar("name", length = 255)
    val hourlyRate: Column<BigDecimal> = decimal("hourly_rate", 10, 2)

    override val primaryKey = PrimaryKey(id)
}

object EquipmentTable : Table("equipment") {
    val id: Column<Int> = integer("id").autoIncrement()
    val studioId: Column<Int> = integer("studio_id").references(StudiosTable.id)
    val status: Column<EquipmentStatus> = enumerationByName("status", 32, EquipmentStatus::class)
    val name: Column<String> = varchar("name", 255)

    override val primaryKey = PrimaryKey(id)
}

object BookingsTable : Table("bookings") {
    val id: Column<Int> = integer("id").autoIncrement()
    val roomId: Column<Int> = integer("room_id").references(RoomsTable.id)
    val userId: Column<Int> = integer("user_id").references(UsersTable.id)
    val startTime: Column<Instant> = timestamp("start_time")
    val endTime: Column<Instant> = timestamp("end_time")
    val totalPriceColumn: Column<BigDecimal> = decimal("total_price", 12, 2)
    val bookingStatus: Column<BookingStatus> = enumerationByName("booking_status", 32, BookingStatus::class)
    val paymentStatus: Column<PaymentStatus> = enumerationByName("payment_status", 32, PaymentStatus::class)
    val createdAt: Column<Instant> = timestamp("created_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)
}
