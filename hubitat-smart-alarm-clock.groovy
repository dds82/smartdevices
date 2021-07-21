import groovy.transform.Field
import java.util.Calendar

metadata {
 	definition (name: "Hubitat Smart Alarm Clock", namespace: "smartdevices", author: "Daniel Segall") {
 		capability "Actuator"
 		capability "Switch"
 		capability "Sensor"
        attribute "alarm","string"
        attribute "Monday", "enum", ["on", "off"]
        attribute "Tuesday", "enum", ["on", "off"]
        attribute "Wednesday", "enum", ["on", "off"]
        attribute "Thursday", "enum", ["on", "off"]
        attribute "Friday", "enum", ["on", "off"]
        attribute "Saturday", "enum", ["on", "off"]
        attribute "Sunday", "enum", ["on", "off"]
        attribute "Shabbat", "enum", ["Default", "Always", "Never"]
        attribute "tripped", "enum", ["true", "false"]
        attribute "SnoozeDuration", "number"
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
         input name: "makerUrl", type: "string", title: "Maker API base URL", required: false, description: "The base URL for the maker API, up to and including 'devices/'"
         input name: "accessToken", type: "string", title: "Maker API access token", required: false, description: "Access token for the maker API"
 	}
 }

@Field static Map configParams = [
        "Sunday": [name: "sunday", type: "bool", title: "Sunday", description: "", required: false],
         "Monday": [name: "monday", type: "bool", title: "Monday", description: "", required: false],
         "Tuesday": [name: "tuesday", type: "bool", title: "Tuesday", description: "", required: false],
         "Wednesday": [name: "wednesday", type: "bool", title: "Wednesday", description: "", required: false],
         "Thursday": [name: "thursday", type: "bool", title: "Thursday", description: "", required: false],
         "Friday": [name: "friday", type: "bool", title: "Friday", description: "", required: false],
         "Saturday": [name: "saturday", type: "bool", title: "Saturday", description: "", required: false]
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
    Saturday_Off()
    Sunday_Off()
 	Monday_On()
    Tuesday_On()
    Wednesday_On()
    Thursday_On()
    Friday_On()
     device.updateSetting("shabbat", "off")
     sendEvent("name":"Shabbat","value":"off")
}
 
 def initialize() {
    doScheduleChange()
 }
 
 def updated() {
        def time = timer.substring(11,16)
         
     configParams.each {
         sendEvent("name":it.key,"value":settings[it.value.name] ? "on" : "off")
     }
     
     device.setLabel(device.getName() + " (" + time +")")
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
    sendEvent("name":"alarm", "value":paramTime)
     tripperOff()
}

def tripperOff() {
    device.updateSetting("tripped", false)
    sendEvent(name:"tripped",value:"false")
}

def changeSnoozeDuration(paramSnooze) {
    device.updateSetting("snoozeDuration", paramSnooze)
    sendEvent("name":"snoozeDuration", "value":paramSnooze)
}

def setDayState(String day, String state) {
    String attr = day.charAt(0).toUpperCase() + day.substring(1).toLowerCase()
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

def doScheduleChange() {
    doUnschedule()
    
    if (timer != null) {
        Calendar cal = Calendar.getInstance()
        cal.setTime(toDateTime(timer))
        
        String cron = "0 ${cal.get(Calendar.MINUTE)} ${cal.get(Calendar.HOUR_OF_DAY)} ? * ${daysOfWeek()}"
        schedule(cron, alarmEvent)
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

def alarmEvent() {
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
    
    if (valid)
        triggerAlarm()
}

def snoozed() {
    triggerAlarm()
}

def triggerAlarm() {
    device.updateSetting("tripped", true)
    sendEvent(name:"tripped",value:"true")
}