package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.event.AppEventBus

class LocalTools(private val context: Context, private val eventBus: AppEventBus) {
    val javascriptTool by lazy { buildJavascriptTool() }
    val timeTool by lazy { buildTimeInfoTool() }
    val clipboardTool by lazy { buildClipboardTool(context) }
    val ttsTool by lazy { buildTextToSpeechTool(eventBus) }
    val askUserTool by lazy { buildAskUserTool() }
    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }
    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }
    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val locationTool by lazy { buildLocationTool(context, eventBus) }
    val notificationTool by lazy { buildNotificationTool(context, eventBus) }
    val appUsageTrajectoryTool by lazy { buildAppUsageTrajectoryTool(context, eventBus) }
    val nearbyPoiTool by lazy { buildNearbyPoiTool(context) }
    val batteryTool by lazy { buildBatteryTool(context) }
    val smsTool by lazy { buildSmsTool(context) }
    val musicControlTool by lazy { buildMusicControlTool(context) }
    val cameraTool by lazy { buildCameraTool(context) }
    val alarmTool by lazy { buildAlarmTool(context, eventBus) }

    val screenEventTool by lazy { buildScreenEventTool() }
    val proactiveMessageTool by lazy { buildProactiveMessageTool(context) }
    val healthDataTool by lazy { buildGadgetbridgeTool(context) }
    val supabaseTool by lazy { buildSupabaseTool(context) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.TimeInfo)) tools.add(timeTool)
        if (options.contains(LocalToolOption.Clipboard)) tools.add(clipboardTool)
        if (options.contains(LocalToolOption.Tts)) tools.add(ttsTool)
        if (options.contains(LocalToolOption.AskUser)) tools.add(askUserTool)
        if (options.contains(LocalToolOption.ScreenTime)) tools.add(screenTimeTool)
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.Location)) tools.add(locationTool)
        if (options.contains(LocalToolOption.Notification)) tools.add(notificationTool)
        if (options.contains(LocalToolOption.AppUsageTrajectory)) tools.add(appUsageTrajectoryTool)
        if (options.contains(LocalToolOption.NearbyPoi)) tools.add(nearbyPoiTool)
        if (options.contains(LocalToolOption.Battery)) tools.add(batteryTool)
        if (options.contains(LocalToolOption.Sms)) tools.add(smsTool)
        if (options.contains(LocalToolOption.MusicControl)) tools.add(musicControlTool)
        if (options.contains(LocalToolOption.Camera)) tools.add(cameraTool)
        if (options.contains(LocalToolOption.Alarm)) tools.add(alarmTool)
        if (options.contains(LocalToolOption.ScreenEvents)) tools.add(screenEventTool)
        if (options.contains(LocalToolOption.ProactiveMessaging)) tools.add(proactiveMessageTool)
        if (options.contains(LocalToolOption.HealthData)) tools.add(healthDataTool)
        if (options.contains(LocalToolOption.Supabase)) tools.add(supabaseTool)
        return tools
    }
}
