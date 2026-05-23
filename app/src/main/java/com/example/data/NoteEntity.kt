package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val snippet: String,
    val modifyDate: Long,
    val colorId: Int,
    val subject: String = "",
    val alertDate: Long = 0,
    val type: String = "note",
    val folderId: Int = 0,
    val themeId: Int = 0,
    val stickyTime: Long = 0,
    val version: Int = 0,
    val deleteTime: Long = 0,
    val alertTag: Int = 0,
    val tag: String = "",
    val createDate: Long,
    val status: String = "normal",
    val extraInfo: String
) {
    // Helper to get title from extraInfo JSON
    val title: String
        get() = try {
            val json = JSONObject(extraInfo)
            json.optString("title", "")
        } catch (e: Exception) {
            ""
        }

    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("snippet", snippet)
        json.put("modifyDate", modifyDate)
        json.put("colorId", colorId)
        json.put("subject", subject)
        json.put("alertDate", alertDate)
        json.put("type", type)
        json.put("folderId", folderId)
        
        val settingObj = JSONObject()
        settingObj.put("themeId", themeId)
        settingObj.put("stickyTime", stickyTime)
        settingObj.put("version", version)
        json.put("setting", settingObj)
        
        json.put("deleteTime", deleteTime)
        json.put("alertTag", alertTag)
        json.put("tag", tag)
        json.put("createDate", createDate)
        json.put("status", status)
        json.put("extraInfo", extraInfo)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): NoteEntity {
            val id = json.optString("id", System.currentTimeMillis().toString())
            val snippet = json.optString("snippet", "")
            val modifyDate = json.optLong("modifyDate", System.currentTimeMillis())
            val createDate = json.optLong("createDate", System.currentTimeMillis())
            val colorId = json.optInt("colorId", 0)
            val subject = json.optString("subject", "")
            val alertDate = json.optLong("alertDate", 0)
            val type = json.optString("type", "note")
            val folderId = json.optInt("folderId", 0)
            
            val settingObj = json.optJSONObject("setting")
            val themeId = settingObj?.optInt("themeId", 0) ?: 0
            val stickyTime = settingObj?.optLong("stickyTime", 0) ?: 0
            val version = settingObj?.optInt("version", 0) ?: 0
            
            val deleteTime = json.optLong("deleteTime", 0)
            val alertTag = json.optInt("alertTag", 0)
            val tag = json.optString("tag", "")
            val status = json.optString("status", "normal")
            val extraInfo = json.optString("extraInfo", "{}")
            
            return NoteEntity(
                id = id,
                snippet = snippet,
                modifyDate = modifyDate,
                colorId = colorId,
                subject = subject,
                alertDate = alertDate,
                type = type,
                folderId = folderId,
                themeId = themeId,
                stickyTime = stickyTime,
                version = version,
                deleteTime = deleteTime,
                alertTag = alertTag,
                tag = tag,
                createDate = createDate,
                status = status,
                extraInfo = extraInfo
            )
        }
    }
}
