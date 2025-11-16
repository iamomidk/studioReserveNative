package com.studioreserve.studios

import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val studioStore = ConcurrentHashMap<UUID, StudioAggregate>()

fun Route.studioRoutes() {
    route("/api/studios") {
        post {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

            val role = principal.toUserRole()
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unable to determine user role from token"))

            if (role != UserRole.STUDIO_OWNER) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only studio owners can create studios"))
            }

            val ownerId = principal.toUserId()
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unable to resolve the current user"))

            val request = call.receive<CreateStudioRequest>()
            request.validationError()?.let { message ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
            }

            val studioId = UUID.randomUUID()
            val sanitizedAmenities = request.amenities
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinct()

            val studioEntity = StudioEntity(
                id = studioId,
                ownerId = ownerId,
                name = request.name.trim(),
                description = request.description.trim(),
                address = request.address.trim(),
                province = request.province.trim(),
                city = request.city.trim(),
                minPricePerHour = request.minPricePerHour,
                maxPricePerHour = request.maxPricePerHour,
                amenities = sanitizedAmenities,
                verificationStatus = StudioVerificationStatus.PENDING,
                createdAtIso = Instant.now().toString()
            )

            val roomEntities = request.rooms.map { it.toRoomEntity(studioId) }.toMutableList()
            val aggregate = StudioAggregate(studioEntity, roomEntities)
            studioStore[studioId] = aggregate

            call.respond(HttpStatusCode.Created, aggregate.toDetailDto())
        }

        get {
            val provinceFilter = call.request.queryParameters["province"]?.trim()?.lowercase()
            val cityFilter = call.request.queryParameters["city"]?.trim()?.lowercase()

            val minPriceParam = call.request.queryParameters["minPrice"]
            val maxPriceParam = call.request.queryParameters["maxPrice"]

            val minPrice = minPriceParam?.toDoubleOrNull()
                ?: if (minPriceParam == null) null else return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("minPrice must be a valid number")
                )

            val maxPrice = maxPriceParam?.toDoubleOrNull()
                ?: if (maxPriceParam == null) null else return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("maxPrice must be a valid number")
                )

            if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("minPrice cannot be greater than maxPrice")
                )
            }

            val studios = studioStore.values
                .asSequence()
                .filter { it.studio.verificationStatus == StudioVerificationStatus.APPROVED }
                .filter { provinceFilter == null || it.studio.province.equals(provinceFilter, ignoreCase = true) }
                .filter { cityFilter == null || it.studio.city.equals(cityFilter, ignoreCase = true) }
                .filter { minPrice == null || it.studio.minPricePerHour >= minPrice }
                .filter { maxPrice == null || it.studio.maxPricePerHour <= maxPrice }
                .map { it.toSummaryDto() }
                .toList()

            call.respond(StudioListResponse(studios))
        }

        get("/{id}") {
            val idParam = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Studio id is required"))

            val studioId = runCatching { UUID.fromString(idParam) }.getOrElse {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid studio id"))
            }

            val aggregate = studioStore[studioId]
                ?.takeIf { it.studio.verificationStatus == StudioVerificationStatus.APPROVED }
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Studio not found"))

            call.respond(aggregate.toDetailDto())
        }
    }
}

private fun CreateStudioRequest.validationError(): String? {
    if (name.isBlank()) return "Studio name is required"
    if (description.isBlank()) return "Studio description is required"
    if (address.isBlank()) return "Studio address is required"
    if (province.isBlank()) return "Province is required"
    if (city.isBlank()) return "City is required"
    if (minPricePerHour <= 0) return "minPricePerHour must be greater than zero"
    if (maxPricePerHour <= 0) return "maxPricePerHour must be greater than zero"
    if (maxPricePerHour < minPricePerHour) return "maxPricePerHour cannot be less than minPricePerHour"

    rooms.forEachIndexed { index, room ->
        room.validationError(index)?.let { return it }
    }
    return null
}

private fun CreateRoomRequest.validationError(index: Int): String? {
    if (name.isBlank()) return "Room #${index + 1} name is required"
    if (description.isBlank()) return "Room #${index + 1} description is required"
    if (pricePerHour <= 0) return "Room #${index + 1} pricePerHour must be greater than zero"
    return null
}

private fun CreateRoomRequest.toRoomEntity(studioId: UUID): RoomEntity = RoomEntity(
    id = UUID.randomUUID(),
    studioId = studioId,
    name = name.trim(),
    description = description.trim(),
    pricePerHour = pricePerHour
)

private fun StudioAggregate.toSummaryDto(): StudioSummaryDto = StudioSummaryDto(
    id = studio.id.toString(),
    name = studio.name,
    city = studio.city,
    province = studio.province,
    minPricePerHour = studio.minPricePerHour,
    maxPricePerHour = studio.maxPricePerHour,
    amenities = studio.amenities
)

private fun StudioAggregate.toDetailDto(): StudioDetailDto = StudioDetailDto(
    id = studio.id.toString(),
    ownerId = studio.ownerId.toString(),
    name = studio.name,
    description = studio.description,
    address = studio.address,
    province = studio.province,
    city = studio.city,
    minPricePerHour = studio.minPricePerHour,
    maxPricePerHour = studio.maxPricePerHour,
    amenities = studio.amenities,
    verificationStatus = studio.verificationStatus,
    createdAt = studio.createdAtIso,
    rooms = rooms.map { it.toDto() }
)

private fun RoomEntity.toDto(): RoomDto = RoomDto(
    id = id.toString(),
    name = name,
    description = description,
    pricePerHour = pricePerHour
)

private fun JWTPrincipal.toUserId(): UUID? {
    val rawId = payload.getClaim("userId").asString()?.takeIf { it.isNotBlank() } ?: payload.subject
    return rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}

private fun JWTPrincipal.toUserRole(): UserRole? {
    val roleValue = payload.getClaim("role").asString() ?: return null
    return runCatching { UserRole.valueOf(roleValue) }.getOrNull()
}

private data class StudioAggregate(
    val studio: StudioEntity,
    val rooms: MutableList<RoomEntity>
)

private data class StudioEntity(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String,
    val address: String,
    val province: String,
    val city: String,
    val minPricePerHour: Double,
    val maxPricePerHour: Double,
    val amenities: List<String>,
    val verificationStatus: StudioVerificationStatus,
    val createdAtIso: String
)

private data class RoomEntity(
    val id: UUID,
    val studioId: UUID,
    val name: String,
    val description: String,
    val pricePerHour: Double
)

@Serializable
private data class StudioListResponse(
    val studios: List<StudioSummaryDto>
)

@Serializable
private data class StudioSummaryDto(
    val id: String,
    val name: String,
    val city: String,
    val province: String,
    val minPricePerHour: Double,
    val maxPricePerHour: Double,
    val amenities: List<String>
)

@Serializable
private data class StudioDetailDto(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String,
    val address: String,
    val province: String,
    val city: String,
    val minPricePerHour: Double,
    val maxPricePerHour: Double,
    val amenities: List<String>,
    val verificationStatus: StudioVerificationStatus,
    val createdAt: String,
    val rooms: List<RoomDto>
)

@Serializable
private data class RoomDto(
    val id: String,
    val name: String,
    val description: String,
    val pricePerHour: Double
)

@Serializable
private data class ErrorResponse(val message: String)

@Serializable
private data class CreateStudioRequest(
    val name: String,
    val description: String,
    val address: String,
    val province: String,
    val city: String,
    val minPricePerHour: Double,
    val maxPricePerHour: Double,
    val amenities: List<String> = emptyList(),
    val rooms: List<CreateRoomRequest> = emptyList()
)

@Serializable
private data class CreateRoomRequest(
    val name: String,
    val description: String,
    val pricePerHour: Double
)

@Serializable
private enum class StudioVerificationStatus {
    PENDING,
    APPROVED,
    REJECTED
}
