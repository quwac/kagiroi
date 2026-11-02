package dev.quwac.kagiroi.sql

/** An immutable [List] whose construction requires at least one value. */
class NonEmptyList<out T> private constructor(val first: T, remaining: List<T>) :
    List<T> by (listOf(first) + remaining) {
    override fun equals(other: Any?): Boolean = other is List<*> && toList() == other

    override fun hashCode(): Int = toList().hashCode()

    override fun toString(): String = toList().toString()

    companion object {
        /** Creates a non-empty list from [first] and optional [remaining] values. */
        fun <T> of(first: T, vararg remaining: T): NonEmptyList<T> = NonEmptyList(first, remaining.toList())

        /** Returns null when [values] has no elements. */
        fun <T> from(values: Iterable<T>): NonEmptyList<T>? {
            val iterator = values.iterator()
            if (!iterator.hasNext()) return null
            val first = iterator.next()
            val remaining = buildList {
                while (iterator.hasNext()) add(iterator.next())
            }
            return NonEmptyList(first, remaining)
        }
    }
}

/** Creates a [NonEmptyList] without a nullable conversion step. */
fun <T> nonEmptyListOf(first: T, vararg remaining: T): NonEmptyList<T> = NonEmptyList.of(first, *remaining)

/** Converts this iterable to a [NonEmptyList], or returns null when it is empty. */
fun <T> Iterable<T>.toNonEmptyListOrNull(): NonEmptyList<T>? = NonEmptyList.from(this)
