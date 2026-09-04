package com.maitre.nopainnoscan

import android.content.Context
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** Un message utile plutôt qu'un « serveur injoignable » pour tout. */
object ApiErrors {
    fun describe(context: Context, e: Throwable): String = when (e) {
        is HttpException -> when (e.code()) {
            401 -> context.getString(R.string.error_unauthorized)
            404 -> context.getString(R.string.error_not_found)
            422 -> context.getString(R.string.error_outdated_server)
            in 500..599 -> context.getString(R.string.error_server, e.code())
            else -> context.getString(R.string.error_http, e.code())
        }
        is SocketTimeoutException -> context.getString(R.string.error_timeout)
        is IOException -> context.getString(R.string.error_offline)
        else -> context.getString(R.string.error_generic, e.message)
    }
}
