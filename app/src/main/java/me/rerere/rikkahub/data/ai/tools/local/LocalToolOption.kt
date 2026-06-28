package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("location")
    data object Location : LocalToolOption()

    @Serializable
    @SerialName("notification")
    data object Notification : LocalToolOption()

    @Serializable
    @SerialName("app_usage_trajectory")
    data object AppUsageTrajectory : LocalToolOption()

    @Serializable
    @SerialName("nearby_poi")
    data object NearbyPoi : LocalToolOption()

    @Serializable
    @SerialName("battery")
    data object Battery : LocalToolOption()

    @Serializable
    @SerialName("sms")
    data object Sms : LocalToolOption()

    @Serializable
    @SerialName("music_control")
    data object MusicControl : LocalToolOption()

    @Serializable
    @SerialName("camera")
    data object Camera : LocalToolOption()

    @Serializable
    @SerialName("alarm")
    data object Alarm : LocalToolOption()

    @Serializable
    @SerialName("screen_events")
    data object ScreenEvents : LocalToolOption()

    @Serializable
    @SerialName("proactive_messaging")
    data object ProactiveMessaging : LocalToolOption()

    @Serializable
    @SerialName("health_data")
    data object HealthData : LocalToolOption()

    @Serializable
    @SerialName("supabase")
    data object Supabase : LocalToolOption()
}
