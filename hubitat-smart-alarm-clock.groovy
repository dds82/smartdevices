import groovy.transform.Field
import java.util.Calendar
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter

metadata {
 	definition (name: "Hubitat Smart Alarm Clock", namespace: "smartdevices", author: "Daniel Segall") {
 		capability "Actuator"
 		capability "Switch"
 		capability "Sensor"
        attribute "Monday", "enum", ["on", "off"]
        attribute "Tuesday", "enum", ["on", "off"]
        attribute "Wednesday", "enum", ["on", "off"]
        attribute "Thursday", "enum", ["on", "off"]
        attribute "Friday", "enum", ["on", "off"]
        attribute "Saturday", "enum", ["on", "off"]
        attribute "Sunday", "enum", ["on", "off"]
        attribute "Shabbat", "enum", ["Default", "Always", "Never"]
        attribute "tripped", "enum", ["true", "false"]
        attribute "preAlarmTripped", "enum", ["true", "false"]
        attribute "SnoozeDuration", "number"
        attribute "editableTime", "string"
        attribute "editableTimeType", "string"
        attribute "alarmTime", "string"
        attribute "alarmTimeType", "enum", [CONSTANT_TIME, SUNRISE_PLUS, SUNRISE_MINUS, SUNSET_PLUS, SUNSET_MINUS]
        attribute "preAlarmTime", "string"
        command "changeAlarmTime", [[name: "Time*", type: "STRING"]]
        command "setAlarmTimeInMinutes", [[name: "Minutes*", type: "NUMBER"]]
        command "setDayState", [[name: "Day*", type: "ENUM", constraints: ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Shabbat"]], [name: "Enabled*", type: "ENUM", constraints: ["on", "off", "default"]]]
        command "snooze"
        command "dismiss"
        command "dismissAndLeaveOn"
        command "constantTime"
        command "sunrisePlus"
        command "sunriseMinus"
        command "sunsetPlus"
        command "sunsetMinus"
        command "nextTimeType"
        command "prevTimeType"
        //command "isValidDay", [[name: "day", type: "STRING"]]
     }
     preferences {
         input name: "timerType", type: "enum", title: "Alarm Time Type", required:true, options: [CONSTANT_TIME, SUNRISE_PLUS, SUNRISE_MINUS, SUNSET_PLUS, SUNSET_MINUS], defaultValue: CONSTANT_TIME
         input name: "timer", type: "time", title: "Alarm Time", description: "Enter Time", required: false
         input name: "eventsEnabled", type: "bool", title: "Fire Events", description: "If turned off, no events will be fired", defaultValue:true, required: false
         if (isAlarmEventsEnabled()) {
             configParams.each {input it.value}
             input name: "shabbat", type: "enum", title: "Shabbat/Yom Tov", description: "", required: false, options: ["off", "on", "default"], defaultValue: "off"
             input name: "shabbatMode", type: "enum", title: "Shabbat Mode name", required:true, options: getModeOptions(), defaultValue: "Shabbat"
             input name: "normal", type: "enum", title: "Normal", description: "Optional quick override to the daily toggles which applies when the Hub's mode is the Normal mode", required: false, options: ["off", "on", "default"], defaultValue: "default"
             input name: "normalMode", type: "enum", title: "Normal Mode name", required:true, options: getModeOptions(), defaultValue: "Home"
             input name: "snoozeDuration", type: "number", title: "Snooze Duration", description: "Minutes", required: false, defaultValue: 10
             input name: "preAlarm", type: "number", title: "Pre-Alarm", description: "How many minutes before the alarm event to set a pre-alarm event", required: false, defaultValue: null
             input name: "momentary", type: "bool", title: "Momentary", description: "If set, the 'tripped' event will behave as a momentary event", required: false, defaultValue: false
         }
         
         input name: "makerApiAppID", type: "string", title: "Maker API App ID", required: false, description: "The Maker API App's app ID.  For example: https://cloud.hubitat.com/api/[UUID]/apps/[AppID]..."
         input name: "hubUUID", type: "string", title: "Hub UUID", required: false, description: "The Hub's UUID for the maker API, for cloud access.  For example: https://cloud.hubitat.com/api/[UUID]/apps/[AppID]..."
         input name: "accessToken", type: "string", title: "Maker API access token", required: false, description: "Access token for the maker API"
 	}
 }

@Field static Map configParams = [
        "Sunday": [name: "sunday", type: "bool", title: "Sunday", description: "", required: false, defaultValue:true],
         "Monday": [name: "monday", type: "bool", title: "Monday", description: "", required: false, defaultValue:true],
         "Tuesday": [name: "tuesday", type: "bool", title: "Tuesday", description: "", required: false, defaultValue:true],
         "Wednesday": [name: "wednesday", type: "bool", title: "Wednesday", description: "", required: false, defaultValue:true],
         "Thursday": [name: "thursday", type: "bool", title: "Thursday", description: "", required: false, defaultValue:true],
         "Friday": [name: "friday", type: "bool", title: "Friday", description: "", required: false, defaultValue:true],
         "Saturday": [name: "saturday", type: "bool", title: "Saturday", description: "", required: false, defaultValue:false]
]

@Field static String[] daysOfWeek = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"]

@Field static String CONSTANT_TIME = "Constant"
@Field static String SUNRISE_PLUS = "Sunrise Plus"
@Field static String SUNRISE_MINUS = "Sunrise Minus"
@Field static String SUNSET_PLUS = "Sunset Plus"
@Field static String SUNSET_MINUS = "Sunset Minus"
@Field static Map TIME_TYPES = ["Constant":"constantTime", "Sunrise Plus":"sunrisePlus", "Sunrise Minus":"sunriseMinus", "Sunset Plus":"sunsetPlus", "Sunset Minus":"sunsetMinus"]
@Field static List TIME_TYPES_ORDERED = ["Constant", "Sunrise Plus", "Sunrise Minus", "Sunset Plus", "Sunset Minus"]

List<String> getModeOptions() {
    List<String> options = new ArrayList<>()
    for (Object mode : location.getModes())
        options.add(mode.toString())
    
    return options
}

def constantTime() {
    setTimeType(CONSTANT_TIME)
}

def sunrisePlus() {
    setTimeType(SUNRISE_PLUS)
}

def sunriseMinus() {
    setTimeType(SUNRISE_MINUS)
}

def sunsetPlus() {
    setTimeType(SUNSET_PLUS)
}

def sunsetMinus() {
    setTimeType(SUNSET_MINUS)
}

def setTimeType(type) {
    device.updateSetting("timerType", [type: "enum", value: type])
    sendEvent(name: "alarmTimeType", value: type)
    doScheduleChange()
    maybeScheduleSunriseSunset(type)
}

 def uninstalled() {
     doUnschedule()
 }

def installed() {
    updated()
    constantTime()
    changeAlarmTime("07:00")
    off()
}
 
 def initialize() {
    doScheduleChange()
 }
 
 def updated() {         
     configParams.each {
         sendEvent("name":it.key,"value":settings[it.value.name] ? "on" : "off")
     }
     
     sendEvent(name: "alarmTimeType", value: timerType)
     doScheduleChange()
     maybeScheduleSunriseSunset()
 }

 def on(){

     sendEvent(name:"switch",value:"on")
     tripperOff()
     doScheduleChange(null, true, "on")
     maybeScheduleSunriseSunset()
 }

 def off(){
     sendEvent(name:"switch",value:"off")
     tripperOff()
     doUnschedule()
     maybeScheduleSunriseSunset()
 } 

  def changeAlarmTime(paramTime) {
     SimpleDateFormat df = new SimpleDateFormat("HH:mm")
     //log.debug "changeAlarmTime ${paramTime} ${df.parse(paramTime)}"
     Date d = df.parse(paramTime)
     device.updateSetting("timer", d)
     tripperOff()
     doScheduleChange(d)
}

def setAlarmTimeInMinutes(totalMinutes) {
    Long min = totalMinutes as long
    long hours = min / 60
    long minutes = min % 60
    changeAlarmTime(hours + ":" + minutes)
}

def preAlarmOff() {
    device.updateSetting("preAlarmTripped", false)
    sendEvent(name:"preAlarmTripped",value:"false")
}

def tripperOff() {
    preAlarmOff()
    device.updateSetting("tripped", false)
    sendEvent(name:"tripped",value:"false")
}

def changeSnoozeDuration(paramSnooze) {
    device.updateSetting("snoozeDuration", paramSnooze)
    sendEvent("name":"snoozeDuration", "value":paramSnooze)
}

def setDayState(String day, String state) {
    String attr = String.valueOf(day.charAt(0).toUpperCase()) + day.substring(1).toLowerCase()
    String setting = day.toLowerCase()
    
    if (setting.equals("shabbat")) {
        device.updateSetting(setting, state)
        sendEvent(name: attr, value: state)
    }
    else if (!"default".equals(state)) {
        boolean on = "on".equals(state)
        if (on) {
            device.updateSetting(setting, true)
            sendEvent(name: attr, value: "on")
            on()
        }
        else {
            device.updateSetting(setting, false)
            sendEvent(name: attr, value: "off")
        }
    }
    
    doScheduleChange()
}

def doUnschedule() {
    unschedule(alarmEvent)
     unschedule(snoozed)
    unschedule(preAlarmEvent)
    unschedule(recalcSunriseSunset)
}

def recalcSunriseSunset() {
    doScheduleChange()
}

def maybeScheduleSunriseSunset(type=null) {
    if (type == null)
        type = timerType
    
    if (type == CONSTANT_TIME) unschedule(recalcSunriseSunset)
    else schedule("0 5 0 * * ?", recalcSunriseSunset, [overwrite : true])
}

String daysOfWeek() {
    if (device.getSetting("shabbat") == "on") {
        return "*"
    }
    
    String str = ""
    configParams.each {
        if (settings[it.value.name]) {
            if (str.length() > 0)
                str += ","
            
            str += it.key.substring(0, 3).toUpperCase()
        }
    }
    
    return str
}

boolean isAlarmEventsEnabled() {
    return (eventsEnabled == null || eventsEnabled)
}

boolean isMomentary() {
    return momentary != null && momentary
}

Calendar applyOffset(Date d) {
    Calendar cal = Calendar.getInstance()
     Calendar settingCal = Calendar.getInstance()
     settingCal.setTime(d)
     String timerType = device.getSetting("timerType") ?: CONSTANT_TIME
     switch (timerType) {
         case SUNRISE_PLUS:
             cal.setTime(location.sunrise)
             cal.add(Calendar.HOUR_OF_DAY, settingCal.get(Calendar.HOUR_OF_DAY))
             cal.add(Calendar.MINUTE, settingCal.get(Calendar.MINUTE))
             break
         
         case SUNRISE_MINUS:
             cal.setTime(location.sunrise)
             cal.add(Calendar.HOUR_OF_DAY, -settingCal.get(Calendar.HOUR_OF_DAY))
             cal.add(Calendar.MINUTE, -settingCal.get(Calendar.MINUTE))
             break
         
         case SUNSET_PLUS:
             cal.setTime(location.sunset)
             cal.add(Calendar.HOUR_OF_DAY, settingCal.get(Calendar.HOUR_OF_DAY))
             cal.add(Calendar.MINUTE, settingCal.get(Calendar.MINUTE))
             break
         
         case SUNSET_MINUS:
             cal.setTime(location.sunset)
             cal.add(Calendar.HOUR_OF_DAY, -settingCal.get(Calendar.HOUR_OF_DAY))
             cal.add(Calendar.MINUTE, -settingCal.get(Calendar.MINUTE))
             break
         
         default:
             cal = settingCal
             break
     }
    
    //log.debug "Time with offset=${cal.getTime()}, offset=${timerType}"    
    return cal
}

def doScheduleChange(sched=null, fireEvent=true, String switchOverride=null) {
    doUnschedule()
    tripperOff()
    def sw = switchOverride == null ? device.currentValue("switch") : switchOverride
    boolean scheduleCron = isAlarmEventsEnabled() && ("on".equals(sw) || "true".equals(sw))
    
    if (!sched)
        sched = timer
    
    if (sched != null) {
        if (!(sched instanceof Date))
            sched = toDateTime(sched)
        
        Calendar cal = applyOffset(sched)
        //log.debug "time is ${cal.getTime()} ${sched}"
        
        SimpleDateFormat df = new SimpleDateFormat("HH:mm")
        
        if (scheduleCron) {
            String cron = "0 ${cal.get(Calendar.MINUTE)} ${cal.get(Calendar.HOUR_OF_DAY)} ? * ${daysOfWeek()}"
            schedule(cron, alarmEvent)
        }
        
        if (preAlarm != null && preAlarm > 0) {
            cal.add(Calendar.MINUTE, -preAlarm.toInteger())
            
            if (scheduleCron) {
                String cron = "0 ${cal.get(Calendar.MINUTE)} ${cal.get(Calendar.HOUR_OF_DAY)} ? * ${daysOfWeek()}"
                schedule(cron, preAlarmEvent)
            }
            
            def preAlarmTimeOnly = df.format(cal.getTime())
            if (fireEvent)
                sendEvent(name: "preAlarmTime", value: preAlarmTimeOnly)
        }
        
        def eventTimeOnly = df.format(cal.getTime())
        def editableTimeOnly = df.format(sched)
        if (fireEvent)
            sendEvent(name: "alarmTime", value: eventTimeOnly)
        
        updateHtmlWidgets(editableTimeOnly)
    }
}

boolean dismissAndLeaveOn() {
    return dismiss(false)
}

boolean dismiss(turnMasterSwitchOff=true) {
    if (tripped) {
        tripperOff()
        if (turnMasterSwitchOff)
            off()
        else
            doScheduleChange()
        
        return true
    }
    
    return false
}

def snooze() {
    if (dismiss(false)) {
        if (isAlarmEventsEnabled())
            runIn(snoozeDuration * 60, snoozed)
    }
}

boolean isValidDay(String d=null) {
    String name = d
    if (name == null) {
        Calendar cal = Calendar.getInstance()
        int day = cal.get(Calendar.DAY_OF_WEEK) - 1
        name = daysOfWeek[day]
    }
    
    boolean result = settings[name]
    //log.debug "${name}=${result}"
    return result
}

boolean validateAlarmEvent() {
    boolean valid = true
    String specialOverride = null
    if (location.mode == shabbatMode) {
        specialOverride = shabbat
    }
    else if (location.mode == normalMode) {
        specialOverride = normal
    }
    
    if (specialOverride != null) {
        if (specialOverride == "off") {
            valid = false
        }
        else if (specialOverride != "on") {
            valid = isValidDay()
        }
    }
    else
        valid = isValidDay()
    
    return valid
}

def preAlarmEvent() {
    if (validateAlarmEvent()) {
        triggerPreAlarm()
        
        if (isMomentary())
            runIn(1, "preAlarmOff")
    }
}

def alarmEvent() {
    if (validateAlarmEvent()) {
        preAlarmOff()
        triggerAlarm()
        
        if (isMomentary())
            runIn(1, "dismissAndLeaveOn")
    }
}

def snoozed() {
    triggerAlarm()
}

def triggerAlarm() {
    device.updateSetting("tripped", true)
    sendEvent(name:"tripped",value:"true")
}

def triggerPreAlarm() {
    device.updateSetting("preAlarmTripped", true)
    sendEvent(name:"preAlarmTripped",value:"true")
}

String declareJavascriptFunction(deviceid, String command, String secondaryValue=null, boolean secondaryJavascript=false) {
    return declareJavascriptFunctionAndReadyStateChange(deviceid, command, secondaryValue, secondaryJavascript, true)
}

String declareJavascriptFunctionAndReadyStateChange(deviceid, String command, String secondaryValue=null, boolean secondaryJavascript=false, boolean includeReadyStateChange=false) {
    String secondaryJs = ""
    String secondary = ""
    if (secondaryJavascript) {
        secondaryJs = "\"+" + secondaryValue + "+\""
        secondary = "/" + secondaryJs
    }
    
    String urlBuilder = "var a=\"" + makerApiAppID + "\";"
    urlBuilder += "var i=\"" + hubUUID + "\";"
    urlBuilder += "var o=window.location.origin;"
    urlBuilder += "var f=origin;"
    urlBuilder += "var u=\"\";"
    urlBuilder += "if(o.toLowerCase().includes(\"cloud.hubitat.com\")){"
    urlBuilder += "u=o+\"/api/\"+i+\"/apps/\"+a;"
    urlBuilder += "}"
    urlBuilder += "else{"
    urlBuilder += "u=origin+\"/apps/api/\"+a;"
    urlBuilder += "}"
    urlBuilder += "u+=\"/devices/" + deviceid + "/" + command + ((!secondaryJavascript && secondaryValue) ? "/" + secondaryValue : "") + "\";"
    
    String s = urlBuilder + "var x=new XMLHttpRequest();"
    s += "x.open(\"GET\", u + \"" + secondary + "?access_token=" + accessToken + "\", true);"
    s += "x.send();"
    
    if (includeReadyStateChange)
        s += declareReadyStateChange(secondaryJs)
    
    return s
}

String declareReadyStateChange(String secondaryJs) {
    String jsLabel = device.label == null ? device.name : device.label;
    jsLabel = jsLabel.replace("\'", "")
    String s = "x.onreadystatechange=function(){"
    s += "if(this.readyState == 4){"
    s += "if(this.status == 200){"
    s += "alert(\"${jsLabel} set to " + secondaryJs + "\");"
    s += "}else{"
    s += "alert(\"Error: \" + x.responseText);"
    s += "}"
    s += "}"
    s += "}"
    return s
}

def updateHtmlWidgets(String time) {
    if (device.getSetting("timerType") == CONSTANT_TIME) {
        String js = declareJavascriptFunction(device.id, "changeAlarmTime", "document.getElementById(\"newtime-${device.id}\").value", true)
        html = "<input id=\"newtime-${device.id}\" type=\"time\" value=\"${time}\" /> <input type=\"button\" value=\"Set\" style=\"padding-left:2px;padding-right:2px\" onclick='javascript:" + js + "' />"
    }
    else {
        Calendar cal = Calendar.getInstance()
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm")
        cal.setTime(sdf.parse(time))
        String js = declareJavascriptFunction(device.id, "setAlarmTimeInMinutes", "document.getElementById(\"newtime-${device.id}\").value", true)
        html = "<input id=\"newtime-${device.id}\" type=\"text\" size=\"4\" value=\"${(cal.get(Calendar.HOUR_OF_DAY) * 60) + cal.get(Calendar.MINUTE)}\" /> minutes <input type=\"button\" value=\"Set\" style=\"padding-left:2px;padding-right:2px\" onclick='javascript:" + js + "' />"
    }
    
    sendEvent(name: "editableTime", value: html)
    
    updateTimeType()
}

String clickableBegin(String command) {
    if (makerApiAppID != null && hubUUID != null && accessToken != null)
        return "<div onclick='javascript:" + declareJavascriptFunctionAndReadyStateChange(device.id, command) + "'>"
    
    return "<div>"
}

String buildTimeTypeBlock(String activeType, String currentType) {
    final String clickableEnd = "</div>"
    
    final String headerBegin = "<span style=\"border:2px outset\">"
    final String headerEnd = "</span>"
    
    final String dimBegin = ""
    final String dimEnd = ""
    
    String text = "<div>"
    if (activeType == currentType)
        text += headerBegin
    else
        text += dimBegin
    text += currentType
    if (activeType == currentType)
        text += headerEnd
    else
        text += dimEnd
        
    text += clickableEnd
}

def updateTimeType() {    
    final String activeType = device.getSetting("timerType") ?: CONSTANT_TIME
    int idx = TIME_TYPES_ORDERED.indexOf(activeType)
        
    String times = clickableBegin("prevTimeType") + "&uarr;" + "</div>"
    times += buildTimeTypeBlock(activeType, TIME_TYPES_ORDERED[prevTimeIdx(idx)]) +  buildTimeTypeBlock(activeType,TIME_TYPES_ORDERED[idx]) +  buildTimeTypeBlock(activeType, TIME_TYPES_ORDERED[nextTimeIdx(idx)])
    times += clickableBegin("nextTimeType") + "&darr;" + "</div>"
    
    // log.debug "Times=" + times.replaceAll("<", "&lt;").replaceAll(">", "&gt;") + " Times length = " + times.length()
    
    sendEvent("name":"editableTimeType", "value": times)
}

int prevTimeIdx(int idx) {
    if (idx == 0)
        return TIME_TYPES_ORDERED.size() - 1
    
    return idx - 1
}

int nextTimeIdx(int idx) {
    return (idx + 1) % TIME_TYPES_ORDERED.size()
}

def nextTimeType() {
    final String activeType = device.getSetting("timerType") ?: CONSTANT_TIME
    int idx = TIME_TYPES_ORDERED.indexOf(activeType)
    setTimeType(TIME_TYPES_ORDERED.get(nextTimeIdx(idx)))
}

def prevTimeType() {
    final String activeType = device.getSetting("timerType") ?: CONSTANT_TIME
    int idx = TIME_TYPES_ORDERED.indexOf(activeType)
    setTimeType(TIME_TYPES_ORDERED.get(prevTimeIdx(idx)))
}
