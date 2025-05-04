metadata {
    definition(name: "Smart Attribute Aggregator Child", namespace: "smartdevices", author: "Daniel Segall", vid: "generic-motion", ocfDeviceType: "x.com.st.d.motion") {
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "IlluminanceMeasurement"
        capability "Battery"
        capability "Sensor"
        capability "Switch"
        capability "Health Check"
		capability "Refresh"

        attribute "dataSource", "string"
        attribute "batteryDevice", "string"
        attribute "mainsDevice", "string"
    }
}

def setDataSource(source) {
    sendEvent(name: "dataSource", value: source)
}

def setBatteryDevice(source) {
    sendEvent(name: "batteryDevice", value: source)
}

def setMainsDevice(source) {
    sendEvent(name: "mainsDevice", value: source)
}

def installed() {
    log.debug "Child device installed"
}

def updated() {
    log.debug "Child device updated"
}

def parse(String description) {
    log.debug "Parsing description: $description"
}

def setAttribute(String name, value) {
    sendEvent(name: name, value: value)
}

def refresh() {
    log.debug "Refreshing child device attributes"

    // Call the parent app to update all attributes of the child device
    def parentApp = getParent()
    if (parentApp) {
        parentApp.setSourceDevicesOnChild()
        parentApp.updateChildAttributes()  // Trigger the parent app to update child attributes
    }
}
