package net.dcnnt.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.lang.Exception

/**
 * Types of config entry
 */
enum class ConfTypes { INT, STRING, BOOL, SELECT, DIR, DOC }


/**
 * One record in config
 */
abstract class DCConfEntry<T: Any>(private val conf: DCConf, val name: String, open val default: T) {
    abstract val type: ConfTypes
    private lateinit var v: T
    val value: T
        get() = v

    abstract fun check(value: Any?): Boolean

    @Suppress("UNCHECKED_CAST")
    fun init(): DCConfEntry<T> {
        val e = this as DCConfEntry<Any>
        conf.entries.add(e)
        conf.entriesByName[e.name] = e
        v = default
        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun setValue(new: Any?) { v = (new as? T) ?: default }

    @Suppress("UNCHECKED_CAST")
    fun updateValue(new: Any?): Boolean {
        val newValue = (new as? T) ?: return false
        val pre = v
        if (check(new)) {
            v = newValue
            if (pre != new) return conf.dump()
            return true
        }
        return false
    }

    open fun valueText(context: Context): String = value.toString()
}

class IntEntry(conf: DCConf, name: String,
               private val min: Int, private val max: Int,
               default: Int): DCConfEntry<Int>(conf, name, default) {
    override val type = ConfTypes.INT
    override fun check(value: Any?): Boolean {
        if (value is Int) return (value >= min) and (value <= max)
        return false
    }
}

open class StringEntry(conf: DCConf, name: String,
                       private val minLength: Int, private val maxLength: Int,
                       default: String): DCConfEntry<String>(conf, name, default) {
    override val type = ConfTypes.STRING
    override fun check(value: Any?): Boolean {
        if (value is String) return (value.length >= minLength) and (value.length <= maxLength)
        return false
    }
}

class BoolEntry(conf: DCConf, name: String,
                default: Boolean): DCConfEntry<Boolean>(conf, name, default) {
    override val type = ConfTypes.BOOL
    override fun check(value: Any?) = value is Boolean
}

data class SelectOption(val value: String, val title: Int)

class SelectEntry(conf: DCConf, name: String,
                  val options: List<SelectOption>,
                  default: Int): DCConfEntry<String>(conf, name, options[default].value) {
    override val type = ConfTypes.SELECT
    override fun check(value: Any?): Boolean {
        if (value is String) return List(options.size){ options[it].value }.contains(value)
        return false
    }

    override fun valueText(context: Context): String {
        for (option in options) if (option.value == value) return context.getString(option.title)
        return "UNKNOWN"
    }

    fun valueIndex(): Int? {
        for (it in options.withIndex()) if (it.value.value == value) return it.index
        return null
    }
}

open class PathEntry(conf: DCConf, name: String, default: String, val persistent: Boolean):
    StringEntry(conf, name, 0, 0xFFFF, default)

class DirEntry(conf: DCConf, name: String, default: String,
               persistent: Boolean = false): PathEntry(conf, name, default, persistent) {
    override val type = ConfTypes.DIR
}

class DocEntry(conf: DCConf, name: String, default: String,
               persistent: Boolean = false): PathEntry(conf, name, default, persistent) {
    override val type = ConfTypes.DOC
}

/**
 * Common part of application and plugin configs
 * @property confName - short name of this configuration for logging or (future) access
 * @property entries - registered configuration entries
 * @property entriesByName - map of entries names to entries
 * @property extra - extra data from JSON file
 */
abstract class DCConf(val path: String) {
    val TAG = "DC/Conf"
    abstract val confName: String
    val entries = mutableListOf<DCConfEntry<Any>>()
    val entriesByName = mutableMapOf<String, DCConfEntry<Any>>()
    var extra = JSONObject()
    protected var needDump = false

    /**
     * Invoked after all load routines
     */
    open fun onLoad() {}

    /**
     * Save JSON object to file (may be replaced with other destination)
     */
    protected open fun dumpJSON(json: JSONObject) {
        File(path).writeText(json.toString(2))
    }

    /**
     * Save config to JSON file
     * @return true on success, false otherwise
     */
    fun dump(): Boolean {
        val json = JSONObject()
        entries.forEach {
            try {
                json.put(it.name, it.value)
            } catch (e: Exception) {
                Log.e(TAG, "Config '$confName': could not put ${it.name} = ${it.value} to JSON - $e")
                return false
            }
        }
        val entriesNames = List(entries.size) { entries[it].name }
        extra.keys().forEach { if (!entriesNames.contains(it)) json.put(it, extra.opt(it)) }
        try {
            dumpJSON(json)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Config '$confName': failed to write JSON to '$path' - $e")
        }
        return false
    }

    /**
     * Drop all value to default and save config to file
     * @return result of dump()
     */
    fun drop(): Boolean {
        extra = JSONObject()
        entries.forEach { it.setValue(it.default) }
        return dump()
    }

    /**
     * Load defined typed config entries, replace with default values if not found
     * @param json - loaded config JSON
     */
    protected open fun loadEntries(json: JSONObject) {
        entries.forEach {
            val value = json.opt(it.name)
            if (it.check(value) and (value != null)) {
                Log.i(TAG, "Config '$confName': extract ${it.name} = $value - ok")
                it.setValue(value as Any)
            } else {
                Log.w(TAG, "Config '$confName': extract ${it.name} = $value - fall back to default (${it.default})")
                it.setValue(it.default)
                needDump = true
            }
        }
    }

    /**
     * Load JSON object from file (may be replaced with other source)
     */
    protected open fun loadJSON() = JSONObject(File(path).readText())

    /**
     * Load extra entries that was not defined in config class
     */
    protected open fun loadExtra(json: JSONObject) {
        val entriesNames = List(entries.size) { entries[it].name }
        json.keys().forEach { if (!entriesNames.contains(it)) extra.put(it, json.opt(it)) }
    }

    /**
     * Load all config data from JSON file, not found or failed registered entries will be replaced
     * using default values, then JSON file will be re-write
     */
    fun load() {
        lateinit var json: JSONObject
        try {
            json = loadJSON()
            Log.i(TAG, "Config '$confName': JSON loaded from '$path'")
        } catch (e: Exception) {
            json = JSONObject()
            Log.e(TAG, "Config '$confName': JSON loading failed - $e")
            needDump = true
        }
        loadEntries(json)
        extra = JSONObject()
        loadExtra(json)
        if (needDump) {
            Log.w(TAG, "Config '$confName': some entries was changed/restored while loading - dump it back")
            dump()
        }
        onLoad()
    }
}
