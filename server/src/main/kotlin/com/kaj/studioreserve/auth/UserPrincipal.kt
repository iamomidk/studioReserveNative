package com.kaj.studioreserve.auth

import io.ktor.server.auth.Principal
import kotlinx.serialization.Serializable

@Serializable
data class UserPrincipal(val userId: String, val role: String) : Principal
