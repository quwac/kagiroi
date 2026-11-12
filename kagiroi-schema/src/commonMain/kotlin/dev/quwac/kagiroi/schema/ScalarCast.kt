package dev.quwac.kagiroi.schema

import kotlin.js.JsName
import kotlin.uuid.Uuid

/** Fluent CAST to a built-in scalar Kotlin type. Use castTo for custom mappings. */
@JsName("scalarCastNonNull")
inline fun <reified T : Any> SqlExpression<out Any>.cast(): CastExpression<T> = cast(this, scalarCastTarget<T>())

/** Nullable fluent CAST preserves SQL NULL. Use castTo for custom mappings. */
@JsName("scalarCastNullable")
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> SqlExpression<out Any?>.cast(): CastExpression<T?> =
    cast(this as SqlExpression<Any?>, scalarCastTarget<T>())

/** Resolves a built-in scalar target without reflection or implementation class names. */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> scalarCastTarget(): SqlCastTarget<T> = when (T::class) {
    String::class -> stringType()
    Boolean::class -> booleanType()
    Int::class -> intType()
    Long::class -> longType()
    Uuid::class -> uuidType()
    else -> throw IllegalArgumentException("Use castTo with an explicit target for this Kotlin type")
} as SqlCastTarget<T>
