import groovy.transform.Field
import java.util.Calendar
import java.text.SimpleDateFormat

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
        attribute "alarmTime", "string"
        attribute "preAlarmTime", "string"
        command "changeAlarmTime", [[name: "Time*", type: "STRING"]]
        command "setDayState", [[name: "Day*", type: "ENUM", constraints: ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Shabbat"]], [name: "Enabled*", type: "ENUM", constraints: ["on", "off", "default"]]]
        command "snooze"
        //command "isValidDay", [[name: "day", type: "STRING"]]
     }
     preferences {
         input name: "timer", type: "time", title: "Alarm Time", description: "Enter Time", required: false
         configParams.each {input it.value}
         input name: "shabbat", type: "enum", title: "Shabbat/Yom Tov", description: "", required: false, options: ["off", "on", "default"], defaultValue: "off"
         input name: "shabbatMode", type: "enum", title: "Shabbat Mode name", required:true, options: getModeOptions(), defaultValue: "Shabbat"
        input name: "snoozeDuration", type: "number", title: "Snooze Duration", description: "Minutes", required: false, defaultValue: 10
         input name: "preAlarm", type: "number", title: "Pre-Alarm", description: "How many minutes before the alarm event to fire a pre-alarm event", required: false, defaultValue: 20
         input name: "makerUrl", type: "string", title: "Maker API base URL", required: false, description: "The base URL for the maker API, up to and including 'devices/'"
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

List<String> getModeOptions() {
    List<String> options = new ArrayList<>()
    for (Object mode : location.getModes())
        options.add(mode.toString())
    
    return options
}

 def uninstalled() {
     doUnschedule()
 }

def installed() {
    updated()
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
     
     doScheduleChange()
 }

 def on(){
 sendEvent(name:"switch",value:"on")
 tripperOff()
 }

 def off(){
 sendEvent(name:"switch",value:"off")
 tripperOff()
 } 

 def changeAlarmTime(paramTime) {
     SimpleDateFormat df = new SimpleDateFormat("HH:mm")
     log.debug "changeAlarmTime ${paramTime} ${df.parse(paramTime)}"
     Date d = df.parse(paramTime)
     device.updateSetting("timer", d)
     tripperOff()
     doScheduleChange(d)
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

def doScheduleChange(sched=null) {
    doUnschedule()
    tripperOff()
    
    if (!sched)
        sched = timer
    
    if (sched != null) {
        if (!(sched instanceof Date))
            sched = toDateTime(sched)
        
        Calendar cal = Calendar.getInstance()
        cal.setTime(sched)
        //log.debug "time is ${cal.getTime()} ${sched}"
        
        SimpleDateFormat df = new SimpleDateFormat("HH:mm")
        
        String cron = "0 ${cal.get(Calendar.MINUTE)} ${cal.get(Calendar.HOUR_OF_DAY)} ? * ${daysOfWeek()}"
        schedule(cron, alarmEvent)
        
        if (preAlarm != null && preAlarm > 0) {
            cal.add(Calendar.MINUTE, -preAlarm.toInteger())
            cron = "0 ${cal.get(Calendar.MINUTE)} ${cal.get(Calendar.HOUR_OF_DAY)} ? * ${daysOfWeek()}"
            schedule(cron, preAlarmEvent)
            
            def preAlarmTimeOnly = df.format(cal.getTime())
            sendEvent(name: "preAlarmTime", value: preAlarmTimeOnly)
        }
        
        def timeOnly = df.format(sched)
        sendEvent(name: "alarmTime", value: timeOnly)
        updateHtmlWidgets(timeOnly)
    }
}

def snooze() {
    if (tripped) {
        tripperOff()
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
    if (location.mode == shabbatMode) {
        if (shabbat == "off") {
            return
        }
        
        if (shabbat != "on") {
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
    }
}

def alarmEvent() {
    if (validateAlarmEvent()) {
        preAlarmOff()
        triggerAlarm()
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
    String url = makerUrl + deviceid + "/" + command + ((!secondaryJavascript && secondaryValue) ? "/" + secondaryValue : "")
    String secondary = ""
    if (secondaryJavascript) {
        secondary = "/\"+" + secondaryValue + "+\""
    }
    
    String s = "var xhttp = new XMLHttpRequest();"
    s += "xhttp.open(\"GET\", \"" + url + secondary + "?access_token=" + accessToken + "\", true);"
    s += "xhttp.send();"
    return s
}

def updateHtmlWidgets(String time) {
    String js = declareJavascriptFunction(device.id, "changeAlarmTime", "document.getElementById(\"newtime\").value", true)
    String html = "<input id=\"newtime\" type=\"time\" value=\"${time}\" /> <input type=\"button\" value=\"Set\" style=\"padding-left:2px;padding-right:2px\" onclick='javascript:" + js + "' />"
    sendEvent(name: "editableTime", value: html)
}
