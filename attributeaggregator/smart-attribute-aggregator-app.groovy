definition(
    name: "Smart Attribute Aggregator",
    namespace: "smartdevices",
    author: "Daniel Segall",
    description: "Aggregates common attributes from two devices based on battery health.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false
)

preferences {
    page(name: "mainPage", title: "Attribute Aggregator Configuration", install: isInstallable(), uninstall: true) {
        section("Devices") {
            input "batteryDevice", "capability.battery", title: "Battery-Powered Device", required: true, multiple: false, submitOnChange: true
            input "mainDevice", "capability.sensor", title: "Non-Battery-Powered Device", required: true, multiple: false, submitOnChange: true
        }
        section("Settings") {
            input "minBattery", "number", title: "Minimum Battery %", defaultValue: 10, range: "1..100", required: true
            input "maxHours", "number", title: "Maximum Hours Since Last Update (optional — ignored if not set or ≤ 0)", required: false
            input "childLabel", "text", title: "Name for Aggregated Child Device", required: true, submitOnChange: true
        }

        if (batteryDevice && mainDevice) {
            def common = getCommonAttributes()
            if (common.isEmpty()) {
                section("⚠️ Warning") {
                    paragraph "⚠️ The selected devices have no attributes in common. Please choose devices with overlapping attributes to continue."
                }
            } else {
                section("Common Attributes Found") {
                    paragraph "These attributes will be aggregated: ${common.join(', ')}"
                }
            }
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    log.debug "App updated"
    unsubscribe()
    initialize()
    checkInstallable()
}

def uninstalled() {
    def child = getChildDevice(getChildDNI())
    if (child) {
        deleteChildDevice(child.deviceNetworkId)
        log.info "Deleted child device: ${child.displayName}"
    }
}

def initialize() {
    if (!batteryDevice || !mainDevice) {
        log.debug "Devices not yet selected. Initialization skipped."
        return
    }

    if (isBatteryPowered(mainDevice)) {
        log.warn "⚠️ The selected 'non-battery-powered' device appears to report battery. Please select a mains-powered device."
        return
    }

    def newLabel = childLabel?.trim() ?: "Aggregated Device"
    def dni = getChildDNI()

    // If the label has changed, delete and recreate the child
    if (state.childLabel && state.childLabel != newLabel) {
        def existing = getChildDevice(dni)
        if (existing) {
            deleteChildDevice(dni)
            log.info "Deleted old child device due to name change"
            state.childDNI = null // Force new UUID
        }
    }

    state.childLabel = newLabel
    createChildDeviceIfNeeded()

    def baseLabel = "Aggregator: ${state.childLabel}"
    if (app.label != baseLabel) {
        app.updateLabel(baseLabel)
    }

    def commonAttrs = getCommonAttributes()
    commonAttrs.each { attr ->
        subscribe(batteryDevice, attr, attributeHandler)
        subscribe(mainDevice, attr, attributeHandler)
    }
    subscribe(batteryDevice, "battery", batteryChangedHandler)
    updateChildAttributes()
}

def isBatteryPowered(dev) {
    if (dev == null) return false
    return dev.hasCapability("Battery") || dev.currentValue("battery") != null
}

def getChildDNI() {
    if (!state.childDNI) {
        state.childDNI = java.util.UUID.randomUUID().toString()
    }
    return state.childDNI
}

def createChildDeviceIfNeeded() {
    def dni = getChildDNI()
    def label = state.childLabel
    if (!getChildDevice(dni)) {
        // Use the custom driver "Smart Attribute Aggregator Child"
        addChildDevice("smartdevices", "Smart Attribute Aggregator Child", dni, [label: label, isComponent: false])
        log.info "Created child device: ${label} using custom driver"
    } else {
        log.debug "Child device already exists"
    }
}

def getCommonAttributes() {
    if (!batteryDevice || !mainDevice) return []
    def attrs1 = batteryDevice.supportedAttributes*.name
    def attrs2 = mainDevice.supportedAttributes*.name
    return attrs1.intersect(attrs2)
}

def attributeHandler(evt) {
    updateChildAttributes()
}

def batteryChangedHandler(evt) {
    def oldBattery = state.lastBattery ?: 0
    def newBattery = (evt.value as Integer) ?: 0
    state.lastBattery = newBattery

    def crossed = (oldBattery < minBattery && newBattery >= minBattery) ||
                  (oldBattery >= minBattery && newBattery < minBattery)

    if (crossed) {
        log.debug "Battery threshold crossed: $oldBattery -> $newBattery"
        updateChildAttributes()
    }
}

def updateChildAttributes() {
    def child = getChildDevice(getChildDNI())
    def now = new Date()
    def lastUpdate = batteryDevice.getLastActivity()
    def timeDiffHours = (now.time - lastUpdate.time) / (1000 * 60 * 60)

    def batteryLevel = batteryDevice.currentBattery ?: 0
    def batteryOK = batteryLevel >= minBattery && (!maxHours || maxHours <= 0 || timeDiffHours <= maxHours)

    def sourceDevice = batteryOK ? batteryDevice : mainDevice
    def sourceType = sourceDevice.displayName

    log.debug "Using ${sourceDevice.displayName} (Battery OK: ${batteryOK}, Level: ${batteryLevel}, Last Update: ${lastUpdate})"

    // Set the dataSource attribute on the child device
    child?.setDataSource(sourceType) // This will update the "dataSource" attribute on the child device

    def commonAttrs = getCommonAttributes()
    commonAttrs.each { attr ->
        try {
            def value = sourceDevice.currentValue(attr)
            if (value != null) {
                child?.sendEvent(name: attr, value: value)
            }
        } catch (e) {
            log.warn "Failed to update attribute '${attr}': ${e.message}"
        }
    }
}


def isInstallable() {
    // Ensure both devices are selected
    if (!batteryDevice || !mainDevice) {
        log.debug "Devices not yet selected. Cannot install."
        return false
    }

    // Ensure the child device name is not empty
    if (!childLabel?.trim()) {
        log.debug "Child device name not set. Cannot install."
        return false
    }

    // Check if the devices have common attributes
    def commonAttrs = getCommonAttributes()
    if (commonAttrs.isEmpty()) {
        log.debug "No common attributes found between the devices. Cannot install."
        return false
    }

    return true
}


def checkInstallable() {
    // Re-check installability after every update.
    if (isInstallable()) {
        app.updateSetting("install", [value: true])  // Force enable the install button.
    } else {
        app.updateSetting("install", [value: false])  // Disable the install button.
    }
}
