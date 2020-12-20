import groovy.transform.Field

metadata {
 	definition (name: "Alexa Virtual Alarm Clock", namespace: "smartdevices", author: "Daniel Segall") {
 		capability "Actuator"
 		capability "Switch"
 		capability "Sensor"
        attribute "alarm","string"
        attribute "Monday", "string"
        attribute "Tuesday", "string"
        attribute "Wednesday", "string"
        attribute "Thursday", "string"
        attribute "Friday", "string"
        attribute "Saturday", "string"
        attribute "Sunday", "string"
        attribute "Shabbat", "string"
        attribute "Snooze", "string"
        attribute "SnoozeDuration", "string"
        attribute "alarmStatus", "string"
        command "changeAlarmTime"
        command "changeSnoozeDuration"
        command "changeAlarmStatus"
        command "Snooze_On"
        command "Snooze_Off"
     }
     preferences {
         input name: "timer", type: "time", title: "Alarm Time", description: "Enter Time", required: false
         configParams.each {input it.value}
 	}
 }

@Field static Map configParams = [
        "Sunday": [name: "sunday", type: "bool", title: "Sunday", description: "", required: false],
         "Monday": [name: "monday", type: "bool", title: "Monday", description: "", required: false],
         "Tuesday": [name: "tuesday", type: "bool", title: "Tuesday", description: "", required: false],
         "Wednesday": [name: "wednesday", type: "bool", title: "Wednesday", description: "", required: false],
         "Thursday": [name: "thursday", type: "bool", title: "Thursday", description: "", required: false],
         "Friday": [name: "friday", type: "bool", title: "Friday", description: "", required: false],
         "Saturday": [name: "saturday", type: "bool", title: "Saturday", description: "", required: false],
         "Shabbat": [name: "shabbat", type: "bool", title: "Shabbat/Yom Tov", description: "", required: false],
]

 def parse() {
 }
 
 def initialize() {
    Saturday_Off()
    Sunday_Off()
 	Monday_On()
    Tuesday_On()
    Wednesday_On()
    Thursday_On()
    Friday_On()
     sendEvent("name":"Shabbat","value":false)
 }
 
 def updated() {
        def time = timer.substring(11,16)
         def tz = location.timeZone
 		def schedTime = time //Today(timer, tz)
 		//def ntime = schedTime.format("H",tz)
 		//def min = schedTime.format("m",tz)
         //def newtime = schedTime.format('HH:mm:ss', tz).toString()
     if(timer) {
     		log.debug "Alarm time set to: $timer"
            // sendEvent("name":"image", "value":schedTime)
             sendEvent("name":"alarm", "value":time)
     } else {
     		log.debug "No departure time is set"
     		}
            
     //  def snooze = snoozeDuration.substring
       sendEvent("name":"snoozeDuration", "value":snoozeDuration)
     configParams.each {
         sendEvent("name":it.key,"value":settings[it.value.name] ? "on" : "off")
     }
       
 }
 def on(){
 sendEvent(name:"switch",value:"on")
 sendEvent("name":"alarmStatus", "value":"Standby")
 }
 def off(){
 sendEvent(name:"switch",value:"off")
 sendEvent(name:"Snooze",value:"off")
 } 
 def changeAlarmTime(paramTime) {
    sendEvent("name":"alarm", "value":paramTime)
}
def changeSnoozeDuration(paramSnooze) {
    sendEvent("name":"snoozeDuration", "value":paramSnooze)
}
def changeAlarmStatus(paramStatus) {
    sendEvent("name":"alarmStatus", "value":paramStatus)
}
def Monday_On(){
 sendEvent(name:"Monday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Monday_Off(){
 sendEvent(name:"Monday",value:"off")
 fireChangeEvent()
 } 
def Tuesday_On(){
 sendEvent(name:"Tuesday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Tuesday_Off(){
 sendEvent(name:"Tuesday",value:"off")
 fireChangeEvent()
 } 
def Wednesday_On(){
 sendEvent(name:"Wednesday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Wednesday_Off(){
 sendEvent(name:"Wednesday",value:"off")
 fireChangeEvent()
 } 
def Thursday_On(){
 sendEvent(name:"Thursday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Thursday_Off(){
 sendEvent(name:"Thursday",value:"off")
 fireChangeEvent()
 } 
def Friday_On(){
 sendEvent(name:"Friday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Friday_Off(){
 sendEvent(name:"Friday",value:"off")
 fireChangeEvent()
 } 
def Saturday_On(){
 sendEvent(name:"Saturday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Saturday_Off(){
 sendEvent(name:"Saturday",value:"off")
 fireChangeEvent()
 } 
def Sunday_On(){
 sendEvent(name:"Sunday",value:"on")
 sendEvent(name:"switch",value:"on")
 fireChangeEvent()
 }
 def Sunday_Off(){
 sendEvent(name:"Sunday",value:"off")
 fireChangeEvent()
 } 
 def Snooze_On(){
 if (device.currentValue("alarmStatus").contains("Active") ){
     sendEvent(name:"Snooze",value:"on",isStateChange: true)
     }  else {refresh.refresh
     		}
 } 
  def Snooze_Off(){
     sendEvent(name:"Snooze",value:"off",isStateChange: true)
 } 
 def fireChangeEvent() {
 	sendEvent(name:"switch", value:device.currentValue("switch"), isStateChange: true)
 }
    
