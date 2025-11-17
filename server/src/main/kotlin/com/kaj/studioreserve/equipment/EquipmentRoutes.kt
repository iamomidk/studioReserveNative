package com.kaj.studioreserve.equipment

import com.kaj.studioreserve.auth.UserPrincipal
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UserRole
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
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val equipmentService = EquipmentService()

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

                when (val creationResult = equipmentService.createEquipment(ownerId, studioId, request)) {
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

                if (role != UserRole.STUDIO_OWNER && role != UserRole.ADMIN) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Role not allowed to view equipment"))
                }

                val ownerId = if (role == UserRole.STUDIO_OWNER) {
                    principal.userId.toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                } else {
                    null
                }

                val studioIdFilter = call.request.queryParameters["studioId"]?.let {
                    it.toUuidOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("studioId query parameter must be a valid UUID"))
                }

                val equipment = equipmentService.listEquipment(role, ownerId, studioIdFilter)
                call.respond(HttpStatusCode.OK, equipment)
            }

            patch("/{id}") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = principal.toUserRole()
                    ?: return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.STUDIO_OWNER && role != UserRole.ADMIN) {
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Role not allowed to update equipment"))
                }

                val userId = principal.userId.toUuidOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val equipmentId = call.parameters["id"]?.toUuidOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("id must be a valid UUID"))

                val request = runCatching { call.receive<UpdateEquipmentRequest>() }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                when (val updateResult = equipmentService.updateEquipment(userId, role, equipmentId, request)) {
                    EquipmentUpdateResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipment not found"))

                    EquipmentUpdateResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("You are not allowed to modify this equipment"))

                    is EquipmentUpdateResult.InvalidPayload ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(updateResult.message))

                    is EquipmentUpdateResult.Success ->
                        call.respond(HttpStatusCode.OK, updateResult.equipment)
                }
            }
        }

        post("/api/equipment/scan") {
            val principal = call.principal<UserPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

            val role = principal.toUserRole()
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

            if (role != UserRole.STUDIO_OWNER && role != UserRole.ADMIN) {
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

            when (val scanResult = equipmentService.scanEquipment(userId, role, request)) {
                EquipmentScanResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipment not found for barcode"))

                EquipmentScanResult.Forbidden ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("You are not allowed to perform this action"))

                EquipmentScanResult.InvalidStatus ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Equipment cannot transition to the requested status"))

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

@Serializable
data class UpdateEquipmentRequest(
    val name: String? = null,
    val brand: String? = null,
    val type: String? = null,
    val rentalPrice: Int? = null,
    val condition: String? = null,
    val serialNumber: String? = null,
    val status: EquipmentStatus? = null,
    val barcodeImageUrl: String? = null
)
