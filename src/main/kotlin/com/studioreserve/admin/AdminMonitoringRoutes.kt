package com.studioreserve.admin

import com.studioreserve.bookings.BookingStatus
import com.studioreserve.bookings.BookingsTable
import com.studioreserve.equipment.EquipmentAction
import com.studioreserve.equipment.EquipmentLogsTable
import com.studioreserve.equipment.EquipmentTable
import com.studioreserve.payments.PaymentStatus
import com.studioreserve.studios.RoomsTable
import com.studioreserve.studios.StudiosTable
import com.studioreserve.users.UsersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq

fun Route.adminMonitoringRoutes() {
    authenticate("auth-jwt") {
        route("/api/admin") {
            get("/bookings") {
                call.ensureAdminPrincipal() ?: return@get

                val bookingStatus = call.request.queryParameters["status"]?.let { statusValue ->
                    runCatching { BookingStatus.valueOf(statusValue.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid booking status filter"))
                    }
                }

                val paymentStatus = call.request.queryParameters["payment_status"]
                    ?: call.request.queryParameters["paymentStatus"]
                val paymentStatusFilter = paymentStatus?.let { value ->
                    runCatching { PaymentStatus.valueOf(value.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid payment status filter"))
                    }
                }

                val fromDate = call.request.queryParameters["fromDate"]?.let { raw ->
                    parseDateTime(raw) ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        AdminErrorResponse("fromDate must be an ISO-8601 date time string")
                    )
                }

                val toDate = call.request.queryParameters["toDate"]?.let { raw ->
                    parseDateTime(raw) ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        AdminErrorResponse("toDate must be an ISO-8601 date time string")
                    )
                }

                val bookings = transaction {
                    val joinStatement = BookingsTable
                        .join(RoomsTable, JoinType.INNER, BookingsTable.roomId, RoomsTable.id)
                        .join(StudiosTable, JoinType.INNER, RoomsTable.studioId, StudiosTable.id)
                        .join(UsersTable, JoinType.INNER, BookingsTable.photographerId, UsersTable.id)

                    val filters = mutableListOf<Op<Boolean>>()

                    bookingStatus?.let { filters += (BookingsTable.bookingStatus eq it) }
                    paymentStatusFilter?.let { filters += (BookingsTable.paymentStatus eq it) }
                    fromDate?.let { filters += (BookingsTable.startTime greaterEq it) }
                    toDate?.let { filters += (BookingsTable.startTime lessEq it) }

                    val statement = if (filters.isEmpty()) {
                        joinStatement.selectAll()
                    } else {
                        val combined = filters.reduce { acc, op -> acc and op }
                        joinStatement.select { combined }
                    }

                    statement
                        .orderBy(BookingsTable.startTime, SortOrder.DESC)
                        .map { row ->
                            AdminBookingSummaryDto(
                                id = row[BookingsTable.id].toString(),
                                roomId = row[RoomsTable.id].toString(),
                                studioName = row[StudiosTable.name],
                                photographerName = row[UsersTable.name],
                                startTime = row[BookingsTable.startTime].toString(),
                                endTime = row[BookingsTable.endTime].toString(),
                                totalPrice = row[BookingsTable.totalPrice].toDouble(),
                                bookingStatus = row[BookingsTable.bookingStatus],
                                paymentStatus = row[BookingsTable.paymentStatus]
                            )
                        }
                }

                call.respond(bookings)
            }

            get("/equipment-logs") {
                call.ensureAdminPrincipal() ?: return@get

                val studioIdFilter = call.request.queryParameters["studioId"]?.let { idValue ->
                    runCatching { UUID.fromString(idValue) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("studioId must be a valid UUID"))
                    }
                }

                val equipmentIdFilter = call.request.queryParameters["equipmentId"]?.let { idValue ->
                    runCatching { UUID.fromString(idValue) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("equipmentId must be a valid UUID"))
                    }
                }

                val actionFilter = call.request.queryParameters["action"]?.let { actionValue ->
                    runCatching { EquipmentAction.valueOf(actionValue.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid equipment action filter"))
                    }
                }

                val fromDate = call.request.queryParameters["fromDate"]?.let { raw ->
                    parseDateTime(raw) ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        AdminErrorResponse("fromDate must be an ISO-8601 date time string")
                    )
                }

                val toDate = call.request.queryParameters["toDate"]?.let { raw ->
                    parseDateTime(raw) ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        AdminErrorResponse("toDate must be an ISO-8601 date time string")
                    )
                }

                val equipmentLogs = transaction {
                    val joinStatement = EquipmentLogsTable
                        .join(EquipmentTable, JoinType.INNER, EquipmentLogsTable.equipmentId, EquipmentTable.id)
                        .join(StudiosTable, JoinType.INNER, EquipmentTable.studioId, StudiosTable.id)
                        .join(UsersTable, JoinType.INNER, EquipmentLogsTable.userId, UsersTable.id)

                    val filters = mutableListOf<Op<Boolean>>()

                    studioIdFilter?.let { filters += (EquipmentTable.studioId eq it) }
                    equipmentIdFilter?.let { filters += (EquipmentLogsTable.equipmentId eq it) }
                    actionFilter?.let { filters += (EquipmentLogsTable.action eq it) }
                    fromDate?.let { filters += (EquipmentLogsTable.timestamp greaterEq it) }
                    toDate?.let { filters += (EquipmentLogsTable.timestamp lessEq it) }

                    val statement = if (filters.isEmpty()) {
                        joinStatement.selectAll()
                    } else {
                        val combined = filters.reduce { acc, op -> acc and op }
                        joinStatement.select { combined }
                    }

                    statement
                        .orderBy(EquipmentLogsTable.timestamp, SortOrder.DESC)
                        .map { row ->
                            AdminEquipmentLogDto(
                                id = row[EquipmentLogsTable.id].toString(),
                                equipmentId = row[EquipmentLogsTable.equipmentId].toString(),
                                equipmentName = row[EquipmentTable.name],
                                studioName = row[StudiosTable.name],
                                userName = row[UsersTable.name],
                                action = row[EquipmentLogsTable.action],
                                timestamp = row[EquipmentLogsTable.timestamp].toString(),
                                notes = row[EquipmentLogsTable.notes]
                            )
                        }
                }

                call.respond(equipmentLogs)
            }
        }
    }
}

@Serializable
data class AdminBookingSummaryDto(
    val id: String,
    val roomId: String,
    val studioName: String,
    val photographerName: String,
    val startTime: String,
    val endTime: String,
    val totalPrice: Double,
    val bookingStatus: BookingStatus,
    val paymentStatus: PaymentStatus
)

@Serializable
data class AdminEquipmentLogDto(
    val id: String,
    val equipmentId: String,
    val equipmentName: String,
    val studioName: String,
    val userName: String,
    val action: EquipmentAction,
    val timestamp: String,
    val notes: String?
)

private fun parseDateTime(raw: String): LocalDateTime? {
    return runCatching { OffsetDateTime.parse(raw).toLocalDateTime() }
        .getOrElse { runCatching { LocalDateTime.parse(raw) }.getOrNull() }
}
