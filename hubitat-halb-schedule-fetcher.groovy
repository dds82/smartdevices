import java.text.SimpleDateFormat
import groovy.xml.XmlUtil
import groovy.transform.Field
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.util.Calendar
import java.util.Date
import java.util.ArrayList

@Field static final String LEV_CHANA = "LC"
@Field static final String ELEMENTARY = "HALB"
@Field static final String SKA = "SKA"
@Field static final String DRS = "DRS"


metadata {
    definition (
        name: "HALB Calendar", 
        namespace: "halbcalendar", 
        author: "dsegall",
    ) {
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"
        attribute LEV_CHANA, "enum", ["true", "false"]
        attribute ELEMENTARY, "enum", ["true", "false"]
        attribute SKA, "enum", ["true", "false"]
        attribute DRS, "enum", ["true", "false"]
        attribute "busing", "enum", ["true", "false"]
        attribute "statusToday", "string"
        attribute "statusTomorrow", "string"
        command "updateStatusText", [[name:"Date", type:"STRING"]]
    }   
}

preferences {
    input("url", "string", title: "Calendar URL", defaultValue: "https://halb.mobi/cal_replace.php?&t0=true&t1=false&t2=true&t3=false&t4=false")
    input("debugEnable", "bool", title: "Enable debug logging")
    input("levChana", "bool", title: "Lev Chana")
    input("elementary", "bool", title: "Elementary")
    input("ska", "bool", title: "SKA")
    input("drs", "bool", title: "DRS")
}

@Field static final SimpleDateFormat MONTH_AND_YEAR = new SimpleDateFormat("MMMM yyyy")
@Field static final String MONTH_CHANGE = "calinnermonthbox"
@Field static final String DAY_CHANGE = "day"
@Field static final String EVENT = "calnamebox"

@Field static final Map SPEAK_MAP = ["LC": "Early Childhood", "HALB": "Elementary School", "SKA": "Girls High School", "DRS": "Boys High School"]

@Field static final String NO_SESSIONS = "No Sessions"
@Field static final String NO_SESSIONS_TYPO = "So Sessions"
@Field static final String NO_BUS = "No District Busing"

// both maps are keyed by date
// this map is a multimap, the values are maps whose keys match the exposed attributes
@Field static final Map rawSchedule = [:]

// this map's values are descriptive strings
@Field static final Map descriptiveSchedule = [:]

@SuppressWarnings('unused')
def installed() {
    log.trace "installed()"
    initialize()
}

def initialize(){
    configure()
}

@SuppressWarnings('unused')
def updated(){
    refresh()
}

@SuppressWarnings('unused')
def configure() {
    if(debugEnable) log.debug "configure()"
    schedule("0 1 0 * * ?", updateStatusText)
}

def refresh() {
    asynchttpGet("parseCalendar", [uri: url + "?t0=true&t1=${levChana}&t2=${elementary}&t3=${ska}&t4=${drs}"])
}

def uninstalled() {
    unschedule(updateStatusText)
}

def parseCalendar(response, data) {
    if(response.getStatus() == 200 || response.getStatus() == 207) {
        rawSchedule.clear()
        descriptiveSchedule.clear()
        String rawData = response.data.trim()
        String validData = rawData
        boolean startsInvalid = rawData.startsWith("</div>")
        while (startsInvalid) {
            validData = rawData.substring("</div>".length())
            rawData = validData
            startsInvalid = rawData.startsWith("</div>")
        }
        
        validData = validData.replaceAll("&", "&amp;")
        validData = validData.replaceAll("<br>", "<br />")
        validData = validData.replaceAll("class=(\\w+)>", "class=\"\$1\">")
        
        String wellFormed = "<root>" + validData + "</root>"
        if (debugEnable) log.debug XmlUtil.escapeXml(wellFormed)
        xmlData = new XmlSlurper().parseText(wellFormed)
        iter = xmlData.depthFirst()
        Calendar cal = Calendar.getInstance()
        
        Map tempSchedule = [:]
        while (iter.hasNext()) {
            node = iter.next()
            clazz = node.@class
            if (clazz == MONTH_CHANGE) {
                Date date = MONTH_AND_YEAR.parse(node.text())
                cal.setTime(date)
            }
            else if (clazz == DAY_CHANGE) {
                cal.set(Calendar.DAY_OF_MONTH, node.text() as int)
            }
            else if (clazz == EVENT) {
                String evt = node.text()
                if (evt.equals(NO_BUSING) || evt.contains(NO_SESSIONS)) {
                    addToSchedule(cal.getTime(), node.text(), tempSchedule)
                }
                else if (evt.contains(NO_SESSIONS_TYPO)) {
                    addToSchedule(cal.getTime(), NO_SESSIONS, tempSchedule)
                }
            }
        }
        
        if (debugEnable) log.debug tempSchedule
        constructSchedule(tempSchedule)
    }
}

void addToSchedule(Date date, String item, Map sched) {
    List items = sched.get(date)
    if (items == null) {
        items = new ArrayList()
        sched.put(date, items)
    }
    
    items.add(item)
}

void constructSchedule(Map sched) {
    sched.each {
        boolean lcNoSessions = false
        boolean halbNoSessions = false
        boolean skaNoSessions = false
        boolean drsNoSessions = false
        boolean globalNoSessions = false
        boolean noBusing = false
        
        lcNoSessions = hasNoSessions(it.value, LEV_CHANA)
        halbNoSessions = hasNoSessions(it.value, ELEMENTARY)
        skaNoSessions = hasNoSessions(it.value, SKA)
        drsNoSessions = hasNoSessions(it.value, DRS)
        
        if (!lcNoSessions && !halbNoSessions && !skaNoSessions && !drsNoSessions) {
            globalNoSessions = hasNoSessions(it.value)
        }
        
        noBusing = hasNoBusing(it.value)
        
        rawSchedule.put(it.key, ["${LEV_CHANA}": !lcNoSessions, "${ELEMENTARY}": !halbNoSessions, "${SKA}": !skaNoSessions, "${DRS}": !drsNoSessions, "school": !globalNoSessions, "busing": !noBusing])
    }
    
    if (debugEnable) log.debug rawSchedule
    constructDescriptiveSchedule()
}

void constructDescriptiveSchedule() {
    rawSchedule.each {outer ->
        List divisionsOff = new ArrayList()
        StringBuilder sb = new StringBuilder()
        boolean everybodyOff = false
        boolean busing = true
        
        outer.value.each {inner ->
            String longForm = SPEAK_MAP[inner.key]
            if (longForm != null) {
                if (!inner.value) divisionsOff.add(longForm)
            }
            else {
                if (inner.key == "school") {
                    if (!inner.value) everybodyOff = true
                }
                else if (inner.key == "busing") {
                    if (!inner.value) busing = false
                }
            }
        }
        
        if (everybodyOff) {
            sb.append("There is no school today.")
        }
        else {
            if (!divisionsOff.isEmpty()) {
                sb.append(divisionsOff.join(", "))
                
                if (divisionsOff.size() == 1) {
                    sb.append(" has ")
                }
                else {
                    int where = sb.lastIndexOf(", ")
                    sb.replace(where, where + 2, " and ")
                    sb.append(" have ")
                }
                
                sb.append("no school today.")
            }
            
            if (sb.length() > 0) sb.append(" ")
            
            if (!busing) {
                sb.append("There is no busing today.")
            }
        }
        
        descriptiveSchedule.put(outer.key, sb.toString())
    }
    
    if (debugEnable) log.debug descriptiveSchedule
    updateStatusText()
}

boolean hasNoSessions(List event, String division=null) {
    String search = division == null ? NO_SESSIONS : division + " " + NO_SESSIONS
    boolean found = false
    event.each {found |= it.contains(search)}
    return found
}
            
boolean hasNoBusing(List event) {
    boolean noBusing = false
    event.each {noBusing |= it.contains(NO_BUS)}
    return noBusing
}

def updateStatusText(String forceDate=null) {
    if (descriptiveSchedule.isEmpty()) {
        refresh()
        return
    }
    
    if (debugEnable) log.debug "updateStatusText forceDate=${forceDate}"
    device.deleteCurrentState("statusToday")
    device.deleteCurrentState("statusTomorrow")
    Calendar cal = Calendar.getInstance()
    if (forceDate == null) {
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.SECOND, 0)
    }
    else {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy")
        Date date = sdf.parse(forceDate)
        cal.setTime(date)
    }
    
    Date today = cal.getTime()
    
    int offset
    switch (cal.get(Calendar.DAY_OF_WEEK)) {
        case Calendar.FRIDAY:
            offset = 3
            break
        
        case Calendar.SATURDAY:
            offset = 2
            break
        
        default:
            offset = 1
            break
    }
    
    cal.add(Calendar.DAY_OF_MONTH, offset)
    Date tomorrow = cal.getTime()
    
    String todayS = descriptiveSchedule.get(today)
    String tomorrowS = descriptiveSchedule.get(tomorrow)
    
    if (debugEnable) log.debug "today=${today} todayS=${todayS} tomorrow=${tomorrow} tomorrowS=${tomorrowS} offset=${offset}"
    
    if (todayS != null) {
        sendEvent("name": "statusToday", "value": todayS)
    }
    
    if (tomorrowS != null) {
        if (offset != 1) {
            tomorrowS = tomorrowS.replaceAll("today", "on Monday")
        }
        else {
            tomorrowS = tomorrowS.replaceAll("today", "tomorrow")
        }
        
        sendEvent("name": "statusTomorrow", "value": tomorrowS)
    }
}
