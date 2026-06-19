package net.dcnnt.core

import org.json.JSONObject
import java.io.File
import java.io.OutputStream


/**
 * Object which can be stored as byte array
 */
interface BytesSerializable {
    /**
     * Serialize object to bytes
     * @return array of bytes
     */
    fun toByteArray(): ByteArray
}

/**
 * Factory object to create another one from raw bytes
 */
interface BytesDeserializer<T> {
    /**
     * Create object from bytes
     * @return ready object
     */
    fun fromByteArray(a: ByteArray): T
}

/**
 * Object which can be stored as JSON string
 */
abstract class JsonSerializable: BytesSerializable {
    /**
     * Pack object data to JSON object
     * @return serializable JSON object
     */
    abstract fun toJson(): JSONObject

    /**
     * Serialize object to JSON string
     * @param minimize - create minimized version of JSON string
     * @return JSON string
     */
    fun toJsonString(minimize: Boolean = false): String {
        if (minimize) {
            return toJson().toString()
        }
        return toJson().toString(2)
    }

    override fun toByteArray(): ByteArray = toJsonString(true).encodeToByteArray()

    /**
     * Write JSON data to output stream
     * @param out - output stream
     */
    fun dump(out: OutputStream) {
        out.write(toByteArray())
    }

    /**
     * Write JSON data to file
     * @param file - file object
     */
    fun dump(file: File) {
        file.writeText(toJsonString())
    }

    /**
     * Write JSON data to file
     * @param path - path to write data to
     */
    fun dump(path: String) {
        File(path).writeText(toJsonString())
    }
}

/**
 * Factory object to create another one from JSON
 */
abstract class JsonDeserializer<T>(): BytesDeserializer<T> {
    /**
     * Create object T from JSON object
     * @return ready object T
     */
    abstract fun fromJson(j: JSONObject): T

    /**
     * Create object T from JSON string
     * @return JSON string
     */
    fun fromJsonString(s: String): T = fromJson(JSONObject(s))

    override fun fromByteArray(a: ByteArray): T = fromJsonString(a.decodeToString())

    /**
     * Load object from JSON file
     * @param file - file object
     */
    fun load(file: File): T = fromJsonString(file.readText())

    /**
     * Load object from JSON file by path
     * @param path - path to read data from
     */
    fun load(path: String): T  = fromJsonString(File(path).readText())
}
