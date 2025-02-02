package ru.sodovaya.kble.utils

import android.annotation.SuppressLint


@SuppressLint("DefaultLocale")
fun Float.wrap(symbolsAfter: Int) = String.format("%.${symbolsAfter}f", this).replace(",", ".")

fun <T> safely(action: () -> T): T? = runCatching { action.invoke() }.getOrNull()

fun String?.midway() = this
    ?.run {
        if (this.startsWith("HW_"))
            this.replace("HW_", "MIDWAY_")
        else this
    } ?: "N/A"