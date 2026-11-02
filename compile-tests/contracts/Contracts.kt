package fixtures

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

data class User(val id: Long)

sealed interface GetUserResponse {
    data class Ok(val body: User) : GetUserResponse
    data object NotFound : GetUserResponse
}

object UserSerializer : KSerializer<User> {
    override val descriptor = buildClassSerialDescriptor("User") {
        element("id", Long.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: User) = encoder.encodeStructure(descriptor) {
        encodeLongElement(descriptor, 0, value.id)
    }

    override fun deserialize(decoder: Decoder): User = decoder.decodeStructure(descriptor) {
        var id: Long? = null
        while (true) {
            when (decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> id = decodeLongElement(descriptor, 0)
                else -> throw SerializationException("Unexpected field")
            }
        }
        User(id ?: throw SerializationException("Missing id"))
    }
}

fun getUser(id: Long): GetUserResponse =
    if (id == 1L) GetUserResponse.Ok(User(id)) else GetUserResponse.NotFound
