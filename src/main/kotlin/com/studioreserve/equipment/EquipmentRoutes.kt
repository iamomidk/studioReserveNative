package com.studioreserve.equipment

import com.studioreserve.auth.UserPrincipal
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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.equipmentRoutes() {
    authenticate("auth-jwt") {
        route("/api/equipment") {
            post {
                val principal = call.principal<UserPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = principal.toUserRole()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.STUDIO_OWNER) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only studio owners can create equipment"))
                }

                val ownerId = principal.userId.toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val request = runCatching { call.receive<CreateEquipmentRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                request.validationError()?.let { message ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                }

                val studioId = request.studioId.toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("studioId must be a valid UUID"))

                val creationResult = transaction {
                    val studioRow = StudiosTable.select { StudiosTable.id eq studioId }.singleOrNull()
                        ?: return@transaction EquipmentCreateResult.StudioNotFound

                    if (studioRow[StudiosTable.ownerId] != ownerId) {
                        return@transaction EquipmentCreateResult.Forbidden
                    }

                    val equipmentId = UUID.randomUUID()
                    val barcodeCode = UUID.randomUUID().toString()

                    EquipmentTable.insert { statement ->
                        statement[EquipmentTable.id] = equipmentId
                        statement[EquipmentTable.studioId] = studioId
                        statement[EquipmentTable.name] = request.name.trim()
                        statement[EquipmentTable.brand] = request.brand.trim()
                        statement[EquipmentTable.type] = request.type.trim()
                        statement[EquipmentTable.rentalPrice] = request.rentalPrice.toBigDecimalWithScale()
                        statement[EquipmentTable.condition] = request.condition.trim()
                        statement[EquipmentTable.serialNumber] = request.serialNumber.trim()
                        statement[EquipmentTable.status] = EquipmentStatus.AVAILABLE
                        statement[EquipmentTable.barcodeCode] = barcodeCode
                        statement[EquipmentTable.barcodeImageUrl] = null
                    }

                    val equipmentRow = EquipmentTable.select { EquipmentTable.id eq equipmentId }.single()
                    EquipmentCreateResult.Success(equipmentRow.toEquipmentDto())
                }

                when (creationResult) {
                    EquipmentCreateResult.StudioNotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Studio not found"))
                    EquipmentCreateResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("You do not own this studio"))
                    is EquipmentCreateResult.Success ->
                        call.respond(HttpStatusCode.Created, creationResult.equipment)
                }
            }

            get {
                val principal = call.principal<UserPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = principal.toUserRole()
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                val ownerId = if (role == UserRole.STUDIO_OWNER) {
                    principal.userId.toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                } else {
                    null
                }

                val studioIdFilter = call.request.queryParameters["studioId"]?.let {
                    it.toUuidOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("studioId query parameter must be a valid UUID"))
                }

                val equipment = transaction {
                    when (role) {
                        UserRole.STUDIO_OWNER -> {
                            val ownerUuid = ownerId ?: return@transaction emptyList()
                            val studioIds = StudiosTable
                                .slice(StudiosTable.id)
                                .select { StudiosTable.ownerId eq ownerUuid }
                                .map { it[StudiosTable.id] }

                            if (studioIds.isEmpty()) {
                                emptyList()
                            } else {
                                EquipmentTable
                                    .select { EquipmentTable.studioId inList studioIds }
                                    .map { it.toEquipmentDto() }
                            }
                        }
                        UserRole.ADMIN -> {
                            EquipmentTable.selectAll().map { it.toEquipmentDto() }
                        }
                        UserRole.PHOTOGRAPHER -> {
                            if (studioIdFilter == null) emptyList() else {
                                EquipmentTable
                                    .select { EquipmentTable.studioId eq studioIdFilter }
                                    .map { it.toEquipmentDto() }
                            }
                        }
                    }
                }

                call.respond(HttpStatusCode.OK, equipment)
            }
        }

        post("/api/equipment/scan") {
            val principal = call.principal<UserPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

            val role = principal.toUserRole()
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

            if (role != UserRole.STUDIO_OWNER && role != UserRole.PHOTOGRAPHER) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Role not allowed to scan equipment"))
            }

            val userId = principal.userId.toUuidOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

            val request = runCatching { call.receive<ScanRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
            }

            if (request.barcodeCode.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("barcodeCode is required"))
            }

            val scanResult = transaction {
                val equipmentRow = EquipmentTable
                    .select { EquipmentTable.barcodeCode eq request.barcodeCode.trim() }
                    .singleOrNull()
                    ?: return@transaction EquipmentScanResult.NotFound

                val studioRow = StudiosTable
                    .select { StudiosTable.id eq equipmentRow[EquipmentTable.studioId] }
                    .single()

                val isOwner = studioRow[StudiosTable.ownerId] == userId

                when (request.action) {
                    EquipmentAction.SCAN_OUT -> {
                        if (!isOwner) return@transaction EquipmentScanResult.Forbidden
                        if (equipmentRow[EquipmentTable.status] != EquipmentStatus.AVAILABLE) {
                            return@transaction EquipmentScanResult.InvalidStatus
                        }

                        EquipmentTable.update({ EquipmentTable.id eq equipmentRow[EquipmentTable.id] }) {
                            it[status] = EquipmentStatus.RENTED
                        }
                    }

                    EquipmentAction.SCAN_IN -> {
                        if (!isOwner) {
                            return@transaction EquipmentScanResult.Forbidden
                        }
                        EquipmentTable.update({ EquipmentTable.id eq equipmentRow[EquipmentTable.id] }) {
                            it[status] = EquipmentStatus.AVAILABLE
                        }
                    }
                }

                val updatedRow = EquipmentTable.select { EquipmentTable.id eq equipmentRow[EquipmentTable.id] }.single()

                val logId = UUID.randomUUID()
                val timestamp = LocalDateTime.now(ZoneOffset.UTC)
                EquipmentLogsTable.insert { statement ->
                    statement[EquipmentLogsTable.id] = logId
                    statement[EquipmentLogsTable.equipmentId] = updatedRow[EquipmentTable.id]
                    statement[EquipmentLogsTable.userId] = userId
                    statement[EquipmentLogsTable.action] = request.action
                    statement[EquipmentLogsTable.timestamp] = timestamp
                    statement[EquipmentLogsTable.notes] = request.notes?.takeIf { it.isNotBlank() }
                }

                val logDto = EquipmentLogDto(
                    id = logId.toString(),
                    equipmentId = updatedRow[EquipmentTable.id].toString(),
                    userId = userId.toString(),
                    action = request.action,
                    timestamp = timestamp.toString(),
                    notes = request.notes?.takeIf { it.isNotBlank() }
                )

                EquipmentScanResult.Success(updatedRow.toEquipmentDto(), logDto)
            }

            when (scanResult) {
                EquipmentScanResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipment not found for barcode"))
                EquipmentScanResult.Forbidden ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("You are not allowed to perform this action"))
                EquipmentScanResult.InvalidStatus ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Equipment cannot be scanned out in its current status"))
                is EquipmentScanResult.Success ->
                    call.respond(HttpStatusCode.OK, EquipmentScanResponse(scanResult.equipment, scanResult.log))
            }
        }

        get("/api/equipment-logs") {
            val principal = call.principal<UserPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

            val role = principal.toUserRole()
                ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

            val ownerId = if (role == UserRole.STUDIO_OWNER) {
                principal.userId.toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
            } else {
                null
            }

            val logs = transaction {
                when (role) {
                    UserRole.ADMIN -> EquipmentLogsTable
                        .selectAll()
                        .orderBy(EquipmentLogsTable.timestamp, SortOrder.DESC)
                        .map { it.toEquipmentLogDto() }

                    UserRole.STUDIO_OWNER -> {
                        val ownerUuid = ownerId ?: return@transaction emptyList()
                        val studioIds = StudiosTable
                            .slice(StudiosTable.id)
                            .select { StudiosTable.ownerId eq ownerUuid }
                            .map { it[StudiosTable.id] }

                        if (studioIds.isEmpty()) {
                            emptyList()
                        } else {
                            val equipmentIds = EquipmentTable
                                .slice(EquipmentTable.id)
                                .select { EquipmentTable.studioId inList studioIds }
                                .map { it[EquipmentTable.id] }

                            if (equipmentIds.isEmpty()) {
                                emptyList()
                            } else {
                                EquipmentLogsTable
                                    .select { EquipmentLogsTable.equipmentId inList equipmentIds }
                                    .orderBy(EquipmentLogsTable.timestamp, SortOrder.DESC)
                                    .map { it.toEquipmentLogDto() }
                            }
                        }
                    }

                    UserRole.PHOTOGRAPHER -> null
                }
            }

            if (logs == null) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Role not allowed to view equipment logs"))
            } else {
                call.respond(HttpStatusCode.OK, logs)
            }
        }
    }
}

private fun ResultRow.toEquipmentDto(): EquipmentDto = EquipmentDto(
    id = this[EquipmentTable.id].toString(),
    studioId = this[EquipmentTable.studioId].toString(),
    name = this[EquipmentTable.name],
    brand = this[EquipmentTable.brand],
    type = this[EquipmentTable.type],
    rentalPrice = this[EquipmentTable.rentalPrice].toInt(),
    condition = this[EquipmentTable.condition],
    serialNumber = this[EquipmentTable.serialNumber],
    status = this[EquipmentTable.status],
    barcodeCode = this[EquipmentTable.barcodeCode],
    barcodeImageUrl = this[EquipmentTable.barcodeImageUrl]
)

private fun ResultRow.toEquipmentLogDto(): EquipmentLogDto = EquipmentLogDto(
    id = this[EquipmentLogsTable.id].toString(),
    equipmentId = this[EquipmentLogsTable.equipmentId].toString(),
    userId = this[EquipmentLogsTable.userId].toString(),
    action = this[EquipmentLogsTable.action],
    timestamp = this[EquipmentLogsTable.timestamp].toString(),
    notes = this[EquipmentLogsTable.notes]
)

private fun CreateEquipmentRequest.validationError(): String? {
    if (studioId.isBlank()) return "studioId is required"
    if (name.isBlank()) return "name is required"
    if (brand.isBlank()) return "brand is required"
    if (type.isBlank()) return "type is required"
    if (rentalPrice <= 0) return "rentalPrice must be greater than zero"
    if (condition.isBlank()) return "condition is required"
    if (serialNumber.isBlank()) return "serialNumber is required"
    return null
}

private fun UserPrincipal.toUserRole(): UserRole? = runCatching { UserRole.valueOf(role) }.getOrNull()

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun Int.toBigDecimalWithScale(): BigDecimal = BigDecimal(this).setScale(2)

@Serializable
private data class ErrorResponse(val message: String)

@Serializable
data class CreateEquipmentRequest(
    val studioId: String,
    val name: String,
    val brand: String,
    val type: String,
    val rentalPrice: Int,
    val condition: String,
    val serialNumber: String
)

@Serializable
data class EquipmentDto(
    val id: String,
    val studioId: String,
    val name: String,
    val brand: String,
    val type: String,
    val rentalPrice: Int,
    val condition: String,
    val serialNumber: String,
    val status: EquipmentStatus,
    val barcodeCode: String,
    val barcodeImageUrl: String?
)

@Serializable
data class EquipmentLogDto(
    val id: String,
    val equipmentId: String,
    val userId: String,
    val action: EquipmentAction,
    val timestamp: String,
    val notes: String?
)

@Serializable
data class ScanRequest(
    val barcodeCode: String,
    val action: EquipmentAction,
    val notes: String? = null
)

@Serializable
data class EquipmentScanResponse(
    val equipment: EquipmentDto,
    val log: EquipmentLogDto
)

private sealed interface EquipmentCreateResult {
    data object StudioNotFound : EquipmentCreateResult
    data object Forbidden : EquipmentCreateResult
    data class Success(val equipment: EquipmentDto) : EquipmentCreateResult
}

private sealed interface EquipmentScanResult {
    data object NotFound : EquipmentScanResult
    data object Forbidden : EquipmentScanResult
    data object InvalidStatus : EquipmentScanResult
    data class Success(val equipment: EquipmentDto, val log: EquipmentLogDto) : EquipmentScanResult
}
