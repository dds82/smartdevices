import java.text.SimpleDateFormat
import groovy.xml.XmlUtil
import groovy.transform.Field
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.util.Calendar
import java.util.Date
import java.util.ArrayList
import java.util.TreeSet

@Field static final String LEV_CHANA = "LC"
@Field static final String ELEMENTARY = "HALB"
@Field static final String MIDDLE_SCHOOL = "MS"
@Field static final String SKA = "SKA"
@Field static final String DRS = "DRS"


metadata {
    definition (
        name: "HALB Calendar JSON", 
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
        attribute "school", "enum", ["true", "false"]
        attribute "statusToday", "string"
        attribute "statusTomorrow", "string"
        attribute "vacation", "enum", ["true", "false"]
        command "updateStatusText", [[name:"Date", type:"STRING"]]
    }   
}

preferences {
    input("debugEnable", "bool", title: "Enable debug logging")
    input("debugJson", "bool", title: "Log JSON")
    input("levChana", "bool", title: "Lev Chana")
    input("elementary", "bool", title: "Elementary")
    input("ska", "bool", title: "SKA")
    input("drs", "bool", title: "DRS")
}

@Field static final SimpleDateFormat MONTH_AND_YEAR = new SimpleDateFormat("MMMM yyyy")

@Field static final Map SPEAK_MAP = ["LC": "Early Childhood", "HALB": "Elementary School", "SKA": "Girls High School", "DRS": "Boys High School", "MS": "Middle School"]
@Field static final Map PREFERENCE_MAP = ["LC": "levChana", "HALB": "elementary", "SKA": "ska", "DRS": "drs"]
@Field static final List ATTRIBUTES = ["LC", "HALB", "SKA", "DRS"]

@Field static final String NO_SESSIONS = "No Sessions"
@Field static final String NO_SESSIONS_TYPO = "So Sessions"
@Field static final String CHOL_HAMOED = "Chol Hamoed"
@Field static final String NO_BUS = "No District Busing"
@Field static final String DISMISSAL_CHANGE = "Dismissal"
@Field static final String LAST_DAY_1 = "Last Day of Sessions"
@Field static final String LAST_DAY_2 = "Last Day of Classes"
@Field static final String FIRST_DAY = "First Day of Classes"

// both maps are keyed by date
// this map is a multimap, the values are maps whose keys match the exposed attributes
@Field static final Map rawSchedule = [:]

// this map is a multimap, the values are maps whose keys match the exposed attributes (plus MS)
@Field static final Map dismissalSchedule = [:]

// this map's values are descriptive strings
@Field static final Map descriptiveSchedule = [:]

// this map's values are maps whose keys are divisions and values are dates
@Field static final Map lastDayMap = [:]

@Field static final TreeMap vacationMap = new TreeMap()

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
    unschedule(nightlyUpdate)
    if(debugEnable) log.debug "configure()"
    schedule("0 1 0 * * ?", nightlyUpdate)
}

def nightlyUpdate() {
    boolean fullRefresh = false
    if (state.lastDayOfSchool != null) {
        Date lastDay = new Date(state.lastDayOfSchool)
        lastDayMap.each {
            if (it.value.after(lastDay)) {
                lastDay = it.value
            }
        }
        
        Date now = new Date()
        fullRefresh = now.after(lastDay)
    }
    
    if (fullRefresh) {
        refresh()
    }
    else {
        updateStatusText()
    }
}

def refresh() {
    asynchttpGet("parseCalendar", [uri: "http://halb.mobi/cal2/events.php"])
}

def uninstalled() {
    unschedule(nightlyUpdate)
}

def parseCalendar(response, data) {
    if(response.getStatus() == 200 || response.getStatus() == 207) {
        rawSchedule.clear()
        descriptiveSchedule.clear()
        dismissalSchedule.clear()
        lastDayMap.clear()
        state.firstDayOfSchool = null
        state.lastDayOfSchool = null
        rawData = parseJson(response.getData())
        if (debugJson) log.debug rawData.custom
        
        Map tempSchedule = [:]
        for (item in rawData.custom) {
            String fullText = item.title
            fullText.split("\\|").each{evt ->
                if (evt.contains(NO_BUS) || evt.contains(NO_SESSIONS) || evt.contains(DISMISSAL_CHANGE) || evt.contains(FIRST_DAY) || evt.contains(LAST_DAY_1) || evt.contains(LAST_DAY_2)) {
                    addToSchedule(toDateTime(item.start), evt, tempSchedule)
                }
                else if (evt.contains(NO_SESSIONS_TYPO) || evt.contains(CHOL_HAMOED)) {
                    // These cases should be school-wide no sessions
                    addToSchedule(toDateTime(item.start), NO_SESSIONS, tempSchedule)
                }
            }
        }
        
        if (debugEnable) log.debug "tempSchedule=${tempSchedule}"
        constructSchedule(tempSchedule)
    }
    else {
        log.error "Server returned error fetching calendar data"
    }
}

boolean isIncluded(String item) {
    if (!levChana && item.contains(LEV_CHANA))
        return false;
    
    if (!elementary && (item.contains(ELEMENTARY) || item.contains(MIDDLE_SCHOOL)))
        return false;
        
    if (!ska && item.contains(SKA))
            return false;
        
    if (!drs && item.contains(DRS))
        return false;
        
    return true;
}

void addToSchedule(Date date, String item, Map sched) {
    if (isIncluded(item)) {
        List items = sched.get(date)
        if (items == null) {
            items = new ArrayList()
            sched.put(date, items)
        }
    
        items.add(item)
    }
}

boolean parseFirstDay(Date when, List event) {
    boolean found = false
    event.each {
        if (it.contains(FIRST_DAY)) {
            state.firstDayOfSchool = when.getTime()
            found = true
            if (debugEnable) log.debug "first day of school is ${firstDayOfSchool}"
        }
    }
    
    return found
}

boolean parseLastDay(Date when, List event) {
    boolean found = false
    event.each {
        if (it.contains(LAST_DAY_1) || it.contains(LAST_DAY_2)) {
            found = true
            if (debugEnable) log.debug "found last day ${it}"
            boolean qualified = false
            ATTRIBUTES.each {division ->
                if (it.contains(division)) {
                    if (debugEnable) log.debug "division is ${division}"
                    qualified = true
                    lastDayMap.put(division, when)
                }
            }
            
            if (!qualified) state.lastDayOfSchool = when.getTime()
        }
    }
    
    return found
}

void constructSchedule(Map sched) {
    List currentVacation = []
    sched.each {
        if (!parseFirstDay(it.key, it.value) && !parseLastDay(it.key, it.value)) {
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
            buildDismissalMap(it.key, it.value)
            if (globalNoSessions) {
                currentVacation.add(it.key)
            }
            else {
                addVacation(currentVacation)
                currentVacation.clear()
            }
        }
    }
    
    if (debugEnable) {
        log.debug "schedule=${rawSchedule}"
        log.debug "dismissal=${dismissalSchedule}"
        log.debug "firstDayOfSchool=${state.firstDayOfSchool} lastDayOfSchool=${state.lastDayOfSchool} divisions=${lastDayMap} vacationMap=${vacationMap}"
    }
    
    constructDescriptiveSchedule()
}

void addVacation(List days) {
    if (days.isEmpty())
        return
    
    Calendar cal = Calendar.getInstance()
    cal.setTime(days.get(days.size() - 1))
    cal.add(Calendar.DAY_OF_MONTH, 1)
    cal.add(Calendar.MILLISECOND, -1)
    Date vacationEnd = cal.getTime()
    
    vacationMap.put(days.get(0), vacationEnd)
}

void constructDescriptiveSchedule() {
    rawSchedule.each {outer ->
        List divisionsOff = new ArrayList()
        Map divisionsEarly = [:]
        StringBuilder sb = new StringBuilder()
        boolean everybodyOff = false
        String everybodyEarly = null
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
        
        Map dismissalMap = dismissalSchedule[outer.key]
        if (dismissalMap != null) {
            dismissalMap.each { dismissal ->
                String longForm = SPEAK_MAP[dismissal.key]
                if (longForm != null) {
                    TreeSet list = divisionsEarly[dismissal.value]
                    if (list == null) {
                        list = new TreeSet()
                        divisionsEarly[dismissal.value] = list
                    }
                    
                    list.add(longForm)
                }
                else {
                    if (dismissal.key == "school") {
                        everybodyEarly = dismissal.value
                    }
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
            
            if (!divisionsEarly.isEmpty()) {
                divisionsEarly.each {early ->
                    sb.append(early.value.join(", "))
                    
                    if (early.value.size() == 1) {
                        sb.append(" has ")
                    }
                    else {
                        int where = sb.lastIndexOf(", ")
                        sb.replace(where, where + 2, " and ")
                        sb.append(" have ")
                    }
                    
                    sb.append(early.key).append(" dismissal today. ")
                }
            }
            
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

boolean buildDismissalMap(Date date, List event) {
    event.each {
        int idx = it.indexOf(DISMISSAL_CHANGE)
        if (idx >= 0) {
            if (debugEnable) log.debug "buildDismissalMap found dismissal event in ${it} at index ${idx}"
            Map dismissalMap = dismissalSchedule.get(date)
            if (dismissalMap == null) {
                dismissalMap = [:]
                dismissalSchedule.put(date, dismissalMap)
            }
            
            if (it.contains(LEV_CHANA)) {
                dismissalMap.put(LEV_CHANA, parseDismissal(it, idx))
            }
            else if (it.contains(ELEMENTARY)) {
                dismissalMap.put(ELEMENTARY, parseDismissal(it, idx))
            }
            else if (it.contains(MIDDLE_SCHOOL)) {
                dismissalMap.put(MIDDLE_SCHOOL, parseDismissal(it, idx))
            }
            else if (it.contains(DRS)) {
                dismissalMap.put(DRS, parseDismissal(it, idx))
            }
            else if (it.contains(SKA)) {
                dismissalMap.put(SKA, parseDismissal(it, idx))
            }
            else {
                dismissalMap.put("school", parseDismissal(it, idx))
            }
        }
    }
}

String parseDismissal(String str, int idx) {
    if (debugEnable) log.debug "Parsing dismissal [${str}] at index ${idx}"
    int end = idx - 2 // the previous index is a space
    if (end >= 0) {
        int start = str.lastIndexOf(" ", end)
        if (start >= 0) {
            String result = str.substring(start + 1, end + 1).toUpperCase()
            if (!result.equalsIgnoreCase("Early") && !str.charAt(start + 1).isDigit()) {
                // ex: "1 PM" instead of "1PM"
                int numberStart = str.lastIndexOf(" ", start - 1)
                result = str.substring(numberStart + 1, start) + result
            }
            
            if (debugEnable) log.debug "Dismissal parsed: ${result}"
            return result
        }
    }
    
    return null;
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
        cal.set(Calendar.MINUTE, 0)
    }
    else {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy")
        Date date = sdf.parse(forceDate)
        cal.setTime(date)
    }
    
    Date today = cal.getTime()
    
    int offset
    final int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    final boolean isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
    switch (dayOfWeek) {
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
    
    if (tomorrowS != null) {
        String tomorrowDay = "tomorrow"
        if (offset != 1) tomorrowDay = "on Monday"
        
        if (!isWeekend && todayS == tomorrowS) {
            // Today and tomorrow have the same schedule
            todayS = todayS.replaceAll("today", "today or " + tomorrowDay)
        }
        else {        
            tomorrowS = tomorrowS.replaceAll("today", tomorrowDay)
            sendEvent("name": "statusTomorrow", "value": tomorrowS)
        }
    }
    
    if (todayS != null && !isWeekend) {
        sendEvent("name": "statusToday", "value": todayS)
    }
    
    Map attrs = rawSchedule.get(today)
    if (attrs != null && !isWeekend) {
        attrs.each {            
            if (shouldSendEvent(it.key)) {
                boolean on = it.value
                if (ATTRIBUTES.contains(it.key as String) && !attrs.get("school")) {
                    on = false
                }
                
                sendEvent(name: it.key, value: on)
            }
            else {
                device.deleteCurrentState(it.key)
            }
        }
    }
    else {
        ATTRIBUTES.each {
            if (shouldSendEvent(it)) {
                sendEvent(name: it, value: !isWeekend && isInSession(today, it) ? "true" : "false")
            }
            else {
                device.deleteCurrentState(it)
            }
        }
        
        boolean sessions = !isWeekend && isInSession(today)
        sendEvent(name: "school", value: sessions ? "true" : "false")
        sendEvent(name: "busing", value: sessions ? "true" : "false")
    }
    
    vacation = vacationMap.floorEntry(today)
    if (vacation != null) {
        Date vacationEnd = vacation.getValue()
        sendEvent(name: "vacation", value: vacationEnd.after(today).toString())
    }
    else {
        sendEvent(name: "vacation", value: "false")
    }
}

boolean shouldSendEvent(String name) {
    String pref = PREFERENCE_MAP[name]
    boolean on = true
    if (pref != null) {
        on = settings[pref] as boolean
    }
    return on
}

boolean isInSession(Date when=null, String division = null) {
    if (when == null) when = new Date()
    Date divisionEnd = division == null ? null : lastDayMap.get(division)
    if (divisionEnd == null) divisionEnd = new Date(state.lastDayOfSchool)
    
    if (state.firstDayOfSchool == null || divisionEnd == null) return false
    Date firstDayOfSchool = new Date(state.firstDayOfSchool)
    
    Calendar cal = Calendar.getInstance()
    cal.setTime(divisionEnd)
    cal.add(Calendar.DAY_OF_MONTH, 1)
    cal.add(Calendar.MILLISECOND, -1)
    divisionEnd = cal.getTime()
    
    boolean result = when.getTime() >= firstDayOfSchool.getTime() && when.getTime() <= divisionEnd.getTime()
    if (debugEnable) log.debug "isInSession ${when}=${result}"
    return result
}
