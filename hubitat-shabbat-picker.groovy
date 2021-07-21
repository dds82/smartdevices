import groovy.transform.Field
import java.util.Date
import java.util.Calendar
import java.util.HashMap
import java.text.SimpleDateFormat

metadata {
 	definition (name: "Shabbat Time Selector", namespace: "smartdevices", author: "Daniel Segall") {
        capability "Actuator"
        capability "PushableButton"
        attribute "regularTime","number"
        attribute "earlyTime","number"
        attribute "plagTime","number"
        attribute "activeTime", "number"
        attribute "times", "string"
        attribute "activeType", "enum", ["Regular", "Plag", "Early"]
        command "setRegularTime", ["long"]
        command "saveCurrentType", ["string"]
        command "restorePreviousType", ["string"]
        command "restorePreviousManualType"
        command "regular"
        command "plag"
        command "early"
     }
 }

@Field static final String SEASONAL = "__seasonal__"
@Field static final String MANUAL_EARLY = "__manual_early__"

 def parse() {
 }

def installed() {
    initialize()
    plag()
}

def createStateMap() {
    if (state.savedTypes == null)
         state.savedTypes = new HashMap()
}
 
 def initialize() {
     createStateMap()
     
    Date regular = new Date(location.sunset.getTime() - (18 * 60000))
     setRegularTime(regular)
 }

def setRegularTime(long date) {
    sendEvent("name":"regularTime", "value":date)
    def activeType = device.getDataValue("activeType")
    if (activeType == null)
        activeType = device.currentValue("activeType")
    
    if (activeType == null)
        activeType = "Plag"
    
    updateActiveTime(activeType, regularTimeOnCalendar(date))
}

def plag() {
    updateActiveTime("Plag")
}

def regular() {
    updateActiveTime("Regular")
}

def early() {
    updateActiveTime("Early")
}
 
 def updated() {
     initialize()
 }

def saveCurrentType(key) {
    saveType(key, device.currentValue("activeType"))
}

def saveType(key, type) {
    createStateMap()
    state.savedTypes[key] = type
}

def restorePreviousType(key) {
    def type = getPreviousType(key)
    if (type)
        updateActiveTime(type)
}

def restorePreviousManualType() {
    restorePreviousType(MANUAL_EARLY)
}

def getPreviousType(key) {
    createStateMap()
    return state.savedTypes[key]
}

def updateActiveTime(type) {
    Calendar regular = regularTimeOnCalendar()
    updateActiveTime(type, regular, false)
}

def updateActiveTime(type, regular, timeChanged = true) {
    int time = (regular.get(Calendar.HOUR_OF_DAY) * 100) + regular.get(Calendar.MINUTE)
    def regularTime = regular.getTimeInMillis()
    
    // plag
    regular.add(Calendar.HOUR_OF_DAY, -1)
    def plagTime = regular.getTimeInMillis()
    
    // 7pm
    regular.add(Calendar.HOUR_OF_DAY, 1)
    regular.set(Calendar.HOUR_OF_DAY, 19)
    regular.set(Calendar.MINUTE, 0)
    def earlyTime = regular.getTimeInMillis()
    
    def activeTime = null
    def prevEarlyOption = state.hasEarlyOption
    
    if (!timeChanged && type != "Regular") {
        saveType(MANUAL_EARLY, type)
    }
    
    boolean earlyOption = time >= 1850
    if (earlyOption) {
        if (timeChanged && prevEarlyOption != null && prevEarlyOption.booleanValue() != earlyOption) {
            type = getPreviousType(SEASONAL)
            saveType(SEASONAL, null)
        }
        
        switch (type) {
            case "Regular":
                activeTime = regularTime
                break
        
            case "Plag":
                activeTime = plagTime
                break
        
            case "Early":
                activeTime = earlyTime
                break
        }
    }
    else {
        if (timeChanged && prevEarlyOption != null && prevEarlyOption.booleanValue() != earlyOption) {
            saveCurrentType(SEASONAL)
        }
        
        activeTime = regularTime
        type = "Regular"
    }
    
    if (timeChanged)
        state.hasEarlyOption = earlyOption
    
    device.updateDataValue("activeType", type)
    sendEvent("name":"activeType", "value":type)
    sendEvent("name":"activeTime", "value":activeTime)
    sendEvent("name":"earlyTime", "value":earlyTime)
    sendEvent("name":"plagTime", "value":plagTime)
    updateTimes(earlyOption, earlyTime, plagTime, regularTime, type)
}

String declareJavascriptFunction(int deviceid, String command) {
    String url = "https://cloud.hubitat.com/api/62feb033-2cf1-437b-91ca-d9fd9150d7c5/apps/47/devices/" + deviceid + "/" + command + "?access_token=5ed1959b-931f-40c1-9e8a-e8536cc0ce48"
    String s = "var xhttp = new XMLHttpRequest();"
    s += "xhttp.open(\"GET\", \"" + url + "\", true);"
    s += "xhttp.send();"
    return s
}

String clickableBegin(String command) {
    return "<div style=\"padding-bottom:12px\" onclick='javascript:" + declareJavascriptFunction(780, command) + "'>"
}

def updateTimes(boolean earlyOption, long earlyTime, long plagTime, long regularTime, String activeType) {
    def times
    
    SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd @ h:mm a")
    final String clickableEnd = "</div>"
    
    final String headerBegin = "<span style=\"border: 2px outset\">"
    final String headerEnd = "</span>"
    
    final String dimBegin = "<i>"
    final String dimEnd = "</i>"
    
    if (earlyOption) {
        String text = clickableBegin("plag")
        if (activeType == "Plag")
            text += headerBegin
        else
            text += dimBegin
        text += "Plag: " + sdf.format(new Date(plagTime))
        if (activeType == "Plag")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        text += clickableBegin("early")
        if (activeType == "Early")
            text += headerBegin
        else
            text += dimBegin
        text += "Early: " + sdf.format(new Date(earlyTime))
        if (activeType == "Early")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        text += clickableBegin("regular")
        if (activeType == "Regular")
            text += headerBegin
        else
            text += dimBegin
        text += "Zman: " + sdf.format(new Date(regularTime))
        if (activeType == "Regular")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        times = text
    }
    else {
        times = sdf.format(new Date(regularTime))
    }
    
    sendEvent("name":"times", "value": times)
}

Calendar regularTimeOnCalendar() {
    return regularTimeOnCalendar(device.currentValue("regularTime"))
}

Calendar regularTimeOnCalendar(currTime) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeInMillis(currTime.longValue())
    return cal
}

def push(num) {
    log.debug "map: ${state.savedTypes}"
    switch (num.toInteger()) {
        case 0:
            plag()
            break
        
        case 1:
            early()
            break
        
        case 2:
            regular()
            break
    }
}
    