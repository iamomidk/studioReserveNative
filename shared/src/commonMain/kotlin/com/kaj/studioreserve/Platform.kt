package com.kaj.studioreserve

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform