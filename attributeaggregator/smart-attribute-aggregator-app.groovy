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
            input "mainDevice", "capability.sensor", title: "Non-Battery-Powered Device(s)", required: true, multiple: true, submitOnChange: true
        }
        section("Settings") {
            input "minBattery", "number", title: "Minimum Battery %", defaultValue: 10, range: "1..100", required: true
            input "maxHours", "number", title: "Maximum Hours Since Last Update (optional — ignored if not set or ≤ 0)", required: false
            input "childLabel", "text", title: "Name for Aggregated Child Device", required: true, submitOnChange: true
            
            // Show aggregator selection only if multiple main devices are selected
            if (mainDevice && ((mainDevice instanceof List && mainDevice.size() > 1) || 
                              (!(mainDevice instanceof List) && false))) {
                input "aggregator", "enum", title: "Aggregation Method", 
                      options: ["Average", "Min", "Max", "Sum"], 
                      defaultValue: "Average", required: true, submitOnChange: true
            }
        }

        if (batteryDevice && mainDevice) {
            // Ensure mainDevice is treated as a list for consistency
            def mainDeviceList = (mainDevice instanceof List) ? mainDevice : [mainDevice]
            def common = getCommonAttributes()
            if (common.isEmpty()) {
                section("⚠️ Warning") {
                    paragraph "⚠️ The selected devices have no attributes in common. Please choose devices with overlapping attributes to continue."
                }
            } else {
                section("Common Attributes Found") {
                    paragraph "These attributes will be aggregated: ${common.join(', ')}"
                    if (mainDeviceList.size() > 1) {
                        paragraph "Multiple main devices selected. Values will be combined using: ${aggregator ?: 'Average'}"
                    }
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
    
    // Handle migration from single to multiple mainDevice
    if (mainDevice && !(mainDevice instanceof List)) {
        log.debug "Migrating single mainDevice to list format"
        def singleDevice = mainDevice
        app.updateSetting("mainDevice", [value: [singleDevice], type: "capability.sensor"])
    }
    
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

def setSourceDevicesOnChild() {
    def child = getChildDevice(getChildDNI())
    child?.setBatteryDevice(batteryDevice)
    child?.setMainsDevice(mainDevice)
}

def initialize() {
    if (!batteryDevice || !mainDevice) {
        log.debug "Devices not yet selected. Initialization skipped."
        return
    }

    // Ensure mainDevice is always treated as a list
    if (!(mainDevice instanceof List)) {
        mainDevice = [mainDevice]
    }

    // Check if any main device is battery powered
    def batteryPoweredMains = mainDevice.findAll { isBatteryPowered(it) }
    if (batteryPoweredMains) {
        log.warn "⚠️ The following 'non-battery-powered' devices appear to report battery: ${batteryPoweredMains*.displayName.join(', ')}. Please select mains-powered devices."
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
        mainDevice.each { device ->
            subscribe(device, attr, attributeHandler)
        }
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
    
    // Ensure mainDevice is always treated as a list
    def mainDeviceList = (mainDevice instanceof List) ? mainDevice : [mainDevice]
    
    def attrs1 = batteryDevice.supportedAttributes*.name
    def allMainAttrs = []
    
    mainDeviceList.each { device ->
        allMainAttrs.addAll(device.supportedAttributes*.name)
    }
    
    // Find attributes that exist in battery device and ALL main devices
    def commonAttrs = attrs1.intersect(allMainAttrs)
    
    // Further filter to ensure the attribute exists in ALL main devices
    commonAttrs = commonAttrs.findAll { attr ->
        mainDeviceList.every { device ->
            device.supportedAttributes*.name.contains(attr)
        }
    }
    
    return commonAttrs
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

def aggregateValues(values, method) {
    if (!values || values.isEmpty()) return null
    
    def numericValues = values.findAll { it != null && (it instanceof Number || it.toString().isNumber()) }
        .collect { it instanceof Number ? it : it.toString() as Double }
    
    if (numericValues.isEmpty()) {
        // For non-numeric values, return the first non-null value
        return values.find { it != null }
    }
    
    switch (method) {
        case "Average":
            return numericValues.sum() / numericValues.size()
        case "Min":
            return numericValues.min()
        case "Max":
            return numericValues.max()
        case "Sum":
            return numericValues.sum()
        default:
            return numericValues.sum() / numericValues.size() // Default to average
    }
}

def updateChildAttributes() {
    def child = getChildDevice(getChildDNI())
    def now = new Date()
    def lastUpdate = batteryDevice.getLastActivity()
    def timeDiffHours = (now.time - lastUpdate.time) / (1000 * 60 * 60)

    def batteryLevel = batteryDevice.currentBattery ?: 0
    def batteryOK = batteryLevel >= minBattery && (!maxHours || maxHours <= 0 || timeDiffHours <= maxHours)

    def sourceType
    def commonAttrs = getCommonAttributes()
    
    if (batteryOK) {
        // Use battery device
        sourceType = batteryDevice.displayName
        log.debug "Using battery device: ${batteryDevice.displayName} (Battery OK: ${batteryOK}, Level: ${batteryLevel})"
        
        child?.setDataSource(sourceType)
        child?.setFailover(false)
        
        commonAttrs.each { attr ->
            try {
                def value = batteryDevice.currentValue(attr)
                if (value != null) {
                    child?.sendEvent(name: attr, value: value)
                }
            } catch (e) {
                log.warn "Failed to update attribute '${attr}' from battery device: ${e.message}"
            }
        }
    } else {
        // Use main device(s) with aggregation if multiple
        def aggregationMethod = (mainDevice.size() > 1) ? (aggregator ?: "Average") : null
        sourceType = mainDevice.size() == 1 ? 
                    mainDevice[0].displayName : 
                    "Aggregated (${aggregationMethod}): ${mainDevice*.displayName.join(', ')}"
        
        log.debug "Using main device(s): ${mainDevice*.displayName.join(', ')} (Battery OK: ${batteryOK}, Aggregation: ${aggregationMethod})"
        
        child?.setDataSource(sourceType)
        child?.setFailover(true)
        
        commonAttrs.each { attr ->
            try {
                if (mainDevice.size() == 1) {
                    // Single main device - no aggregation needed
                    def value = mainDevice[0].currentValue(attr)
                    if (value != null) {
                        child?.sendEvent(name: attr, value: value)
                    }
                } else {
                    // Multiple main devices - aggregate values
                    def values = mainDevice.collect { device ->
                        device.currentValue(attr)
                    }
                    
                    def aggregatedValue = aggregateValues(values, aggregationMethod)
                    if (aggregatedValue != null) {
                        child?.sendEvent(name: attr, value: aggregatedValue)
                        log.debug "Aggregated ${attr}: ${values} -> ${aggregatedValue} (${aggregationMethod})"
                    }
                }
            } catch (e) {
                log.warn "Failed to update attribute '${attr}' from main device(s): ${e.message}"
            }
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
