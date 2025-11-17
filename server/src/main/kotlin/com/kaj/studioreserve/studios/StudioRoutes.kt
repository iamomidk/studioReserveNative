package com.kaj.studioreserve.studios

import com.kaj.studioreserve.auth.AuthorizationException
import com.kaj.studioreserve.auth.currentUserId
import com.kaj.studioreserve.auth.requireRole
import com.kaj.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.studioRoutes() {
    route("/api/studios") {
        authenticate("auth-jwt") {
            post {
                val role = runCatching { call.requireRole(UserRole.STUDIO_OWNER) }.getOrElse { throwable ->
                    val authEx = throwable as? AuthorizationException
                        ?: return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(throwable.localizedMessage ?: "Unexpected error")
                        )
                    return@post call.respond(authEx.status, ErrorResponse(authEx.message))
                }

                if (role != UserRole.STUDIO_OWNER) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Studio owner role required"))
                }

                val ownerId = runCatching { call.currentUserId() }.getOrElse { throwable ->
                    val authEx = throwable as? AuthorizationException
                        ?: return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(throwable.localizedMessage ?: "Unexpected error")
                        )
                    return@post call.respond(authEx.status, ErrorResponse(authEx.message))
                }

                val request = try {
                    call.receive<CreateStudioRequest>()
                } catch (cause: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                }

                request.validationError()?.let { error ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                }

                val normalizedPhotos = request.photos.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                val studio = transaction {
                    val studioId = UUID.randomUUID()
                    StudiosTable.insert { statement ->
                        statement[id] = studioId
                        statement[ownerId] = ownerId
                        statement[name] = request.name.trim()
                        statement[description] = request.description.trim()
                        statement[province] = request.province.trim()
                        statement[city] = request.city.trim()
                        statement[address] = request.address.trim()
                        statement[mapCoordinates] = request.mapCoordinates.trim()
                        statement[photos] = normalizedPhotos
                        statement[verificationStatus] = VerificationStatus.PENDING
                    }

                    StudiosTable
                        .select { StudiosTable.id eq studioId }
                        .single()
                        .toStudioDto()
                }

                call.respond(HttpStatusCode.Created, studio)
            }

            put("/{id}") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown user role"))

                if (role != UserRole.STUDIO_OWNER) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Studio owner role required"))
                }

                val ownerId = runCatching { UUID.fromString(principal.userId) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val studioId = call.parameters["id"]?.let { id ->
                    runCatching { UUID.fromString(id) }.getOrNull()
                } ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid studio id"))

                val request = try {
                    call.receive<UpdateStudioRequest>()
                } catch (cause: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                }

                request.validationError()?.let { error ->
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                }

                val normalizedPhotos = request.photos.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                val result = transaction {
                    val studioRow = StudiosTable
                        .select { StudiosTable.id eq studioId }
                        .singleOrNull()
                        ?: return@transaction StudioUpdateResult.NotFound

                    if (studioRow[StudiosTable.ownerId] != ownerId) {
                        return@transaction StudioUpdateResult.Forbidden
                    }

                    StudiosTable.update({ StudiosTable.id eq studioId }) { statement ->
                        statement[name] = request.name.trim()
                        statement[description] = request.description.trim()
                        statement[province] = request.province.trim()
                        statement[city] = request.city.trim()
                        statement[address] = request.address.trim()
                        statement[mapCoordinates] = request.mapCoordinates.trim()
                        statement[photos] = normalizedPhotos
                    }

                    val updatedRow = StudiosTable
                        .select { StudiosTable.id eq studioId }
                        .single()

                    StudioUpdateResult.Success(updatedRow.toStudioDto())
                }

                when (result) {
                    StudioUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Studio not found"))
                    StudioUpdateResult.Forbidden -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("You do not own this studio"))
                    is StudioUpdateResult.Success -> call.respond(HttpStatusCode.OK, result.studio)
                }
            }
        }

        get {
            val provinceFilter = call.request.queryParameters["province"]?.takeIf(String::isNotBlank)
            val cityFilter = call.request.queryParameters["city"]?.takeIf(String::isNotBlank)

            // TODO: Support price-based filters when pricing data becomes available.

            val studios = transaction {
                val initialCondition: Op<Boolean> = StudiosTable.verificationStatus eq VerificationStatus.APPROVED
                val conditions = listOfNotNull(
                    provinceFilter?.let { StudiosTable.province eq it },
                    cityFilter?.let { StudiosTable.city eq it }
                ).fold(initialCondition) { acc, op -> acc and op }

                StudiosTable
                    .select { conditions }
                    .map { it.toStudioDto() }
            }

            call.respond(studios)
        }

        get("/{id}") {
            val studioId = call.parameters["id"]?.let { id ->
                runCatching { UUID.fromString(id) }.getOrNull()
            } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid studio id"))

            val detail = transaction {
                val studioRow = StudiosTable
                    .select {
                        (StudiosTable.id eq studioId) and (StudiosTable.verificationStatus eq VerificationStatus.APPROVED)
                    }
                    .singleOrNull()
                    ?: return@transaction null

                val rooms = RoomsTable
                    .select { RoomsTable.studioId eq studioId }
                    .map { it.toRoomDto() }

                StudioDetailDto(
                    studio = studioRow.toStudioDto(),
                    rooms = rooms
                )
            } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Studio not found"))

            call.respond(detail)
        }
    }
}

private fun ResultRow.toStudioDto(): StudioDto = StudioDto(
    id = this[StudiosTable.id].toString(),
    ownerId = this[StudiosTable.ownerId].toString(),
    name = this[StudiosTable.name],
    description = this[StudiosTable.description],
    province = this[StudiosTable.province],
    city = this[StudiosTable.city],
    address = this[StudiosTable.address],
    mapCoordinates = this[StudiosTable.mapCoordinates],
    photos = this[StudiosTable.photos],
    verificationStatus = this[StudiosTable.verificationStatus].name,
    createdAt = this[StudiosTable.createdAt].toString()
)

private fun ResultRow.toRoomDto(): RoomDto = RoomDto(
    id = this[RoomsTable.id].toString(),
    studioId = this[RoomsTable.studioId].toString(),
    name = this[RoomsTable.name],
    description = this[RoomsTable.description],
    hourlyPrice = this[RoomsTable.hourlyPrice],
    dailyPrice = this[RoomsTable.dailyPrice],
    features = this[RoomsTable.features],
    images = this[RoomsTable.images]
)

@Serializable
data class StudioDto(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String,
    val province: String,
    val city: String,
    val address: String,
    val mapCoordinates: String,
    val photos: List<String>,
    val verificationStatus: String,
    val createdAt: String
)

@Serializable
data class RoomDto(
    val id: String,
    val studioId: String,
    val name: String,
    val description: String,
    val hourlyPrice: Int,
    val dailyPrice: Int,
    val features: List<String>,
    val images: List<String>
)

@Serializable
data class StudioDetailDto(
    val studio: StudioDto,
    val rooms: List<RoomDto>
)

@Serializable
data class CreateStudioRequest(
    val name: String,
    val description: String,
    val province: String,
    val city: String,
    val address: String,
    val mapCoordinates: String,
    val photos: List<String> = emptyList()
)

@Serializable
data class UpdateStudioRequest(
    val name: String,
    val description: String,
    val province: String,
    val city: String,
    val address: String,
    val mapCoordinates: String,
    val photos: List<String> = emptyList()
)

@Serializable
data class ErrorResponse(val message: String)

private fun CreateStudioRequest.validationError(): String? {
    if (name.isBlank()) return "name is required"
    if (description.isBlank()) return "description is required"
    if (province.isBlank()) return "province is required"
    if (city.isBlank()) return "city is required"
    if (address.isBlank()) return "address is required"
    if (mapCoordinates.isBlank()) return "mapCoordinates is required"
    if (photos.any { it.isBlank() }) return "photos cannot contain blank entries"
    return null
}

private fun UpdateStudioRequest.validationError(): String? = CreateStudioRequest(
    name = name,
    description = description,
    province = province,
    city = city,
    address = address,
    mapCoordinates = mapCoordinates,
    photos = photos
).validationError()

private sealed class StudioUpdateResult {
    data class Success(val studio: StudioDto) : StudioUpdateResult()
    object NotFound : StudioUpdateResult()
    object Forbidden : StudioUpdateResult()
}
