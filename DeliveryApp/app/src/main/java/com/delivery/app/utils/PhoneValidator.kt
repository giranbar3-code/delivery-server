package com.delivery.app.utils

object PhoneValidator {

    private val syrianPhoneRegex = Regex("^(\\+963|00963|0)?9\\d{8}$")
    private val internationalPhoneRegex = Regex("^\\+?[1-9]\\d{6,14}$")

    fun isValid(phone: String): Boolean {
        val cleanPhone = phone.trim().replace("[\\s\\-\\(\\)]".toRegex(), "")
        return syrianPhoneRegex.matches(cleanPhone) || internationalPhoneRegex.matches(cleanPhone)
    }

    fun cleanPhone(phone: String): String {
        return phone.trim().replace("[\\s\\-\\(\\)]".toRegex(), "")
    }

    fun getErrorMessage(phone: String): String {
        return when {
            phone.isBlank() -> "رقم الهاتف مطلوب"
            !isValid(phone) -> "رقم الهاتف غير صالح. مثال: 09xxxxxxxx أو +9639xxxxxxxx"
            else -> ""
        }
    }
}
