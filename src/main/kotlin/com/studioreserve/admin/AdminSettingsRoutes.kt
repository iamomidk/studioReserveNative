package com.studioreserve.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.singleOrNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.adminSettingsRoutes() {
    authenticate("auth-jwt") {
        route("/api/admin/settings") {
            get("/commission") {
                call.ensureAdminPrincipal() ?: return@get

                val settings = transaction { fetchOrCreateCommissionSettings().toCommissionSettingsDto() }
                call.respond(settings)
            }

            put("/commission") {
                call.ensureAdminPrincipal() ?: return@put

                val request = runCatching { call.receive<UpdateCommissionSettingsRequest>() }.getOrElse {
                    return@put call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid request payload"))
                }

                if (request.commissionRate !in 0.0..1.0) {
                    return@put call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("commissionRate must be between 0.0 and 1.0"))
                }

                val updated = transaction {
                    val existing = CommissionSettingsTable.selectAll().singleOrNull()
                    val now = LocalDateTime.now()
                    if (existing == null) {
                        CommissionSettingsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[commissionRate] = BigDecimal.valueOf(request.commissionRate)
                            it[updatedAt] = now
                        }
                    } else {
                        CommissionSettingsTable.update({ CommissionSettingsTable.id eq existing[CommissionSettingsTable.id] }) {
                            it[commissionRate] = BigDecimal.valueOf(request.commissionRate)
                            it[updatedAt] = now
                        }
                    }

                    fetchOrCreateCommissionSettings().toCommissionSettingsDto()
                }

                call.respond(updated)
            }
        }
    }
}

@Serializable
data class CommissionSettingsDto(
    val commissionRate: Double,
    val updatedAt: String
)

@Serializable
data class UpdateCommissionSettingsRequest(val commissionRate: Double)

private fun fetchOrCreateCommissionSettings(): ResultRow {
    return CommissionSettingsTable.selectAll().singleOrNull() ?: run {
        val now = LocalDateTime.now()
        val id = UUID.randomUUID()
        CommissionSettingsTable.insert {
            it[CommissionSettingsTable.id] = id
            it[commissionRate] = BigDecimal.valueOf(0.10)
            it[updatedAt] = now
        }
        CommissionSettingsTable.selectAll().single()
    }
}

private fun ResultRow.toCommissionSettingsDto(): CommissionSettingsDto = CommissionSettingsDto(
    commissionRate = this[CommissionSettingsTable.commissionRate].toDouble(),
    updatedAt = this[CommissionSettingsTable.updatedAt].toString()
)
