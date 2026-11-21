/**
 *  Daiken Split System (Driver)
 *
 *  Ported from Apoc182/local_daikin for Hubitat Elevation
 *  Communicates locally via HTTP POST to http://<ip>:80/dsiot/multireq
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final Map MODE_FROM_HEX = [
    "0000": "fan_only",
    "0100": "heat",
    "0200": "cool",
    "0300": "auto",
    "0500": "dry"
]

@Field static final Map HEX_FROM_MODE = [
    "fan_only": "0000",
    "heat"    : "0100",
    "cool"    : "0200",
    "auto"    : "0300",
    "dry"     : "0500"
]

@Field static final Map FAN_FROM_HEX = [
    "0A00": "Auto",
    "0B00": "Quiet",
    "0300": "Level 1",
    "0400": "Level 2",
    "0500": "Level 3",
    "0600": "Level 4",
    "0700": "Level 5"
]

@Field static final Map HEX_FROM_FAN = [
    "Auto"   : "0A00",
    "Quiet"  : "0B00",
    "Level 1": "0300",
    "Level 2": "0400",
    "Level 3": "0500",
    "Level 4": "0600",
    "Level 5": "0700"
]

metadata {
    definition(
        name: "Daiken Split System",
        namespace: "kurtsanders",
        author: "kurt@kurtsanders.com"
    ) {
        capability "Thermostat"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "EnergyMeter"
        capability "Refresh"

        attribute "outdoorTemperature", "number"
        attribute "daikinMode", "string"
        attribute "fanSpeed", "string"
        attribute "verticalSwing", "string"
        attribute "horizontalSwing", "string"
        attribute "econoMode", "string"
        attribute "runtimeToday", "number"

        command "setFanSpeed", [[
            name: "Fan Speed*",
            type: "ENUM",
            constraints: ["Auto", "Quiet", "Level 1", "Level 2", "Level 3", "Level 4", "Level 5"]
        ]]
        command "setVerticalSwing", [[
            name: "Vertical Swing*",
            type: "ENUM",
            constraints: ["On", "Off"]
        ]]
        command "setHorizontalSwing", [[
            name: "Horizontal Swing*",
            type: "ENUM",
            constraints: ["On", "Off"]
        ]]
        command "setEconoMode", [[
            name: "Econo Mode*",
            type: "ENUM",
            constraints: ["On", "Off"]
        ]]
        command "econoOn"
        command "econoOff"
        command "dry"
        command "fanOnly"
    }

    preferences {
        input name: "ipOverride", type: "text", title: "IP Address Override (optional if set by Manager App)", required: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["off", "heat", "cool", "auto"]))
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto", "on", "circulate"]))
    refresh()
}

def updated() {
    if (settings.ipOverride) {
        updateDataValue("ipAddress", settings.ipOverride.trim())
    }
    if (logEnable) runIn(1800, "logsOff")
    refresh()
}

def logsOff() {
    log.warn "Debug logging disabled automatically."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private String getUnitIp() {
    return (settings.ipOverride ?: getDataValue("ipAddress"))?.trim()
}

def refresh() {
    String ip = getUnitIp()
    if (!ip) {
        log.warn "No IP address configured for ${device.displayName}."
        return
    }

    Map payload = [
        requests: [
            [op: 2, to: "/dsiot/edge/adr_0100.dgc_status?filter=pv"]
        ]
    ]

    sendDsiotPost(ip, payload, "poll")
}

private void sendDsiotPost(String ip, Map payload, String action) {
    String bodyJson = JsonOutput.toJson(payload)
    if (logEnable) log.debug "POST http://${ip}:80/dsiot/multireq (${action}): ${bodyJson}"

    Map params = [
        uri: "http://${ip}:80/dsiot/multireq",
        requestContentType: "application/json",
        contentType: "application/json",
        body: bodyJson,
        timeout: 8
    ]
    asynchttpPost("handleDsiotResponse", params, [action: action])
}

def handleDsiotResponse(resp, data) {
    if (resp.hasError()) {
        log.warn "Daikin DSIOT HTTP error (${data?.action}): ${resp.getStatus()} - ${resp.getErrorMessage()}"
        return
    }

    def json = resp.getJson()
    if (logEnable) log.debug "Response (${data?.action}): ${json}"

    def firstResp = json?.responses?.getAt(0)
    if (!firstResp) return

    if (data?.action == "write") {
        if (firstResp.rsc == 2004) {
            runInMillis(600, "refresh")
        } else {
            log.warn "Daikin write returned unexpected status rsc=${firstResp.rsc}"
        }
        return
    }

    if (firstResp.rsc != 2000) {
        log.warn "Daikin poll returned unexpected status rsc=${firstResp.rsc}"
        return
    }

    parseDgcStatus(firstResp.pc)
}

private void parseDgcStatus(Map pc) {
    def root = findChildNode(pc, "e_1002")
    if (!root) return

    // 1. Power State (e_A002 -> p_01)
    def eA002 = findChildNode(root, "e_A002")
    String powerHex = getParamValue(eA002, "p_01") ?: "00"
    boolean isOn = (powerHex == "01")
    sendEventIfChanged("switch", isOn ? "on" : "off")

    // 2. Modes, Setpoints, Fan & Louver Swing (e_3001)
    def e3001 = findChildNode(root, "e_3001")
    String modeHex = getParamValue(e3001, "p_01") ?: "0300"
    String rawMode = MODE_FROM_HEX[modeHex] ?: "auto"
    sendEventIfChanged("daikinMode", rawMode)
    state.lastRawMode = rawMode

    String hubitatMode = "off"
    if (isOn) {
        switch (rawMode) {
            case "heat":     hubitatMode = "heat"; break
            case "cool":     hubitatMode = "cool"; break
            case "auto":     hubitatMode = "auto"; break
            case "dry":      hubitatMode = "cool"; break
            case "fan_only": hubitatMode = "off";  break
            default:         hubitatMode = "auto"; break
        }
    }
    sendEventIfChanged("thermostatMode", hubitatMode)

    String operatingState = "idle"
    if (isOn) {
        switch (rawMode) {
            case "heat":     operatingState = "heating"; break
            case "cool":
            case "dry":      operatingState = "cooling"; break
            case "fan_only": operatingState = "fan only"; break
            case "auto":     operatingState = "heating"; break
        }
    }
    sendEventIfChanged("thermostatOperatingState", operatingState)

    // Setpoints (0.5 C increments in hex)
    String coolHex = getParamValue(e3001, "p_02")
    String heatHex = getParamValue(e3001, "p_03")
    String autoHex = getParamValue(e3001, "p_1D")

    BigDecimal coolC = coolHex ? (Integer.parseInt(coolHex, 16) / 2.0) : 22.0
    BigDecimal heatC = heatHex ? (Integer.parseInt(heatHex, 16) / 2.0) : 20.0
    BigDecimal autoC = autoHex ? (Integer.parseInt(autoHex, 16) / 2.0) : 21.0

    Integer displayCool = formatTempForHub(coolC)
    Integer displayHeat = formatTempForHub(heatC)
    Integer activeSetpoint = (rawMode == "auto") ? formatTempForHub(autoC) : ((rawMode == "heat") ? displayHeat : displayCool)

    String unitStr = "°${location.temperatureScale}"
    sendEventIfChanged("coolingSetpoint", displayCool, unitStr)
    sendEventIfChanged("heatingSetpoint", displayHeat, unitStr)
    sendEventIfChanged("thermostatSetpoint", activeSetpoint, unitStr)

    // Fan speed for active mode
    String fanParam = getFanParamForMode(rawMode)
    String fanHex = fanParam ? getParamValue(e3001, fanParam) : "0A00"
    String fanLabel = FAN_FROM_HEX[fanHex] ?: "Auto"
    sendEventIfChanged("fanSpeed", fanLabel)
    sendEventIfChanged("thermostatFanMode", (fanLabel == "Auto") ? "auto" : "on")

    // Vertical swing for active mode
    String vSwingParam = getVerticalSwingParamForMode(rawMode)
    String vSwingHex = vSwingParam ? getParamValue(e3001, vSwingParam) : "00000000"
    boolean vSwingOn = (vSwingHex != null && !vSwingHex.startsWith("00"))
    sendEventIfChanged("verticalSwing", vSwingOn ? "On" : "Off")

    // Horizontal swing for active mode
    String hSwingParam = getHorizontalSwingParamForMode(rawMode)
    String hSwingHex = hSwingParam ? getParamValue(e3001, hSwingParam) : "00000000"
    boolean hSwingOn = (hSwingHex != null && !hSwingHex.startsWith("00"))
    sendEventIfChanged("horizontalSwing", hSwingOn ? "On" : "Off")

    // 3. Special Modes: Econo (e_3003 -> p_24)
    def e3003 = findChildNode(root, "e_3003")
    String econoHex = getParamValue(e3003, "p_24") ?: "00"
    boolean isEcono = !(econoHex in ["00", "0000"])
    sendEventIfChanged("econoMode", isEcono ? "On" : "Off")

    // 4. Sensors: Indoor Temp, Outdoor Temp, Humidity (e_A00B)
    def eA00B = findChildNode(root, "e_A00B")
    String inHex  = getParamValue(eA00B, "p_01")
    String humHex = getParamValue(eA00B, "p_02")
    String outHex = getParamValue(eA00B, "p_05")

    if (inHex && inHex.length() >= 2) {
        BigDecimal inC = parseIndoorTempHex(inHex)
        sendEventIfChanged("temperature", formatTempForHub(inC), unitStr)
    }

    if (outHex && outHex.length() >= 2 && outHex != "00" && outHex != "0000") {
        BigDecimal outC = parseOutdoorTempHex(outHex)
        sendEventIfChanged("outdoorTemperature", formatTempForHub(outC), unitStr)
    }

    if (humHex && humHex.length() >= 2) {
        int hum = Integer.parseInt(humHex.substring(0, 2), 16)
        sendEventIfChanged("humidity", hum, "%")
    }

    // 5. Energy & Runtime Today (e_2028)
    def e2028 = findChildNode(root, "e_2028")
    String energyHex = getParamValue(e2028, "p_03")
    String runtimeHex = getParamValue(e2028, "p_04")

    if (energyHex && energyHex.length() >= 4) {
        Integer kwh = Math.round(parseLittleEndianUnsigned16(energyHex) / 10.0) as Integer
        sendEventIfChanged("energy", kwh, "kWh")
    }
    if (runtimeHex && runtimeHex.length() >= 4) {
        int mins = parseLittleEndianUnsigned16(runtimeHex)
        sendEventIfChanged("runtimeToday", mins, "min")
    }
}

// ============================================================================
// Commands
// ============================================================================

def on() {
    writeDsiotNodes([
        [pn: "e_A002", pch: [[pn: "p_01", pv: "01"]]]
    ])
}

def off() {
    writeDsiotNodes([
        [pn: "e_A002", pch: [[pn: "p_01", pv: "00"]]]
    ])
}

def setThermostatMode(String mode) {
    switch (mode?.toLowerCase()) {
        case "off":
            off()
            break
        case "heat":
            setDaikinModeAndPower("0100", true)
            break
        case "cool":
            setDaikinModeAndPower("0200", true)
            break
        case "auto":
            setDaikinModeAndPower("0300", true)
            break
        case "emergency heat":
            setDaikinModeAndPower("0100", true)
            break
        default:
            log.warn "Unsupported thermostat mode requested: ${mode}"
    }
}

def heat() { setThermostatMode("heat") }
def cool() { setThermostatMode("cool") }
def auto() { setThermostatMode("auto") }
def emergencyHeat() { setThermostatMode("heat") }
def dry() { setDaikinModeAndPower("0500", true) }
def fanOnly() { setDaikinModeAndPower("0000", true) }

private void setDaikinModeAndPower(String modeHex, boolean powerOn) {
    writeDsiotNodes([
        [pn: "e_A002", pch: [[pn: "p_01", pv: powerOn ? "01" : "00"]]],
        [pn: "e_3001", pch: [[pn: "p_01", pv: modeHex]]]
    ])
}

def setCoolingSetpoint(temp) {
    String hex = tempToDaikinHex(temp as BigDecimal, 18.0, 32.0)
    List params = [[pn: "p_02", pv: hex]]
    if ((state.lastRawMode ?: "") == "auto") {
        String autoHex = tempToDaikinHex(temp as BigDecimal, 18.0, 30.0)
        params << [pn: "p_1D", pv: autoHex]
    }
    writeDsiotNodes([[pn: "e_3001", pch: params]])
}

def setHeatingSetpoint(temp) {
    String hex = tempToDaikinHex(temp as BigDecimal, 10.0, 30.0)
    List params = [[pn: "p_03", pv: hex]]
    if ((state.lastRawMode ?: "") == "auto") {
        String autoHex = tempToDaikinHex(temp as BigDecimal, 18.0, 30.0)
        params << [pn: "p_1D", pv: autoHex]
    }
    writeDsiotNodes([[pn: "e_3001", pch: params]])
}

def setThermostatFanMode(String fanMode) {
    switch (fanMode?.toLowerCase()) {
        case "auto":
            setFanSpeed("Auto")
            break
        case "circulate":
            setFanSpeed("Quiet")
            break
        case "on":
            setFanSpeed("Level 3")
            break
        default:
            setFanSpeed("Auto")
    }
}

def fanAuto() { setFanSpeed("Auto") }
def fanOn() { setFanSpeed("Level 3") }
def fanCirculate() { setFanSpeed("Quiet") }

def setFanSpeed(String speedLabel) {
    String hex = HEX_FROM_FAN[speedLabel] ?: "0A00"
    String rawMode = state.lastRawMode ?: "auto"
    String paramName = getFanParamForMode(rawMode)
    if (!paramName) {
        log.warn "Fan speed cannot be changed in ${rawMode} mode."
        return
    }
    writeDsiotNodes([[pn: "e_3001", pch: [[pn: paramName, pv: hex]]]])
}

def setVerticalSwing(String stateStr) {
    String hex = (stateStr?.equalsIgnoreCase("On")) ? "0F000000" : "00000000"
    String rawMode = state.lastRawMode ?: "auto"
    String paramName = getVerticalSwingParamForMode(rawMode) ?: "p_05"
    writeDsiotNodes([[pn: "e_3001", pch: [[pn: paramName, pv: hex]]]])
}

def setHorizontalSwing(String stateStr) {
    String hex = (stateStr?.equalsIgnoreCase("On")) ? "0F000000" : "00000000"
    String rawMode = state.lastRawMode ?: "auto"
    String paramName = getHorizontalSwingParamForMode(rawMode) ?: "p_06"
    writeDsiotNodes([[pn: "e_3001", pch: [[pn: paramName, pv: hex]]]])
}

def setEconoMode(String stateStr) {
    String hex = (stateStr?.equalsIgnoreCase("On")) ? "01" : "00"
    writeDsiotNodes([[pn: "e_3003", pch: [[pn: "p_24", pv: hex]]]])
}

def econoOn() { setEconoMode("On") }
def econoOff() { setEconoMode("Off") }

def setSchedule(schedule) {
    // Required by Hubitat Thermostat capability; no-op
}

private void writeDsiotNodes(List e1002Children) {
    String ip = getUnitIp()
    if (!ip) {
        log.warn "Cannot send command: no IP address configured."
        return
    }

    Map payload = [
        requests: [[
            op: 3,
            to: "/dsiot/edge/adr_0100.dgc_status",
            pc: [
                pn: "dgc_status",
                pch: [[
                    pn: "e_1002",
                    pch: e1002Children
                ]]
            ]
        ]]
    ]

    sendDsiotPost(ip, payload, "write")
}

// ============================================================================
// Helpers
// ============================================================================

private String getFanParamForMode(String rawMode) {
    switch (rawMode) {
        case "cool":     return "p_09"
        case "heat":     return "p_0A"
        case "auto":     return "p_28"
        case "fan_only": return "p_26"
        default:         return null
    }
}

private String getVerticalSwingParamForMode(String rawMode) {
    switch (rawMode) {
        case "cool":     return "p_05"
        case "heat":     return "p_07"
        case "auto":     return "p_20"
        case "fan_only": return "p_22"
        case "dry":      return "p_24"
        default:         return "p_05"
    }
}

private String getHorizontalSwingParamForMode(String rawMode) {
    switch (rawMode) {
        case "cool":     return "p_06"
        case "heat":     return "p_08"
        case "auto":     return "p_21"
        case "fan_only": return "p_23"
        case "dry":      return "p_25"
        default:         return "p_06"
    }
}

private Map findChildNode(Map parent, String pn) {
    return parent?.pch?.find { it.pn == pn } as Map
}

private String getParamValue(Map parent, String pn) {
    return findChildNode(parent, pn)?.pv?.toString()
}

private int parseLittleEndianUnsigned16(String hex4) {
    int low = Integer.parseInt(hex4.substring(0, 2), 16)
    int high = Integer.parseInt(hex4.substring(2, 4), 16)
    return (high << 8) | low
}

private int parseLittleEndianSigned16(String hex4) {
    int val = parseLittleEndianUnsigned16(hex4)
    return (val > 32767) ? (val - 65536) : val
}

private BigDecimal parseIndoorTempHex(String hexStr) {
    if (hexStr.length() >= 4) {
        int raw16 = parseLittleEndianSigned16(hexStr.substring(0, 4))
        if (Math.abs(raw16) > 120) return raw16 / 10.0
        return raw16 / 2.0
    }
    int raw8 = Integer.parseInt(hexStr.substring(0, 2), 16)
    if (raw8 > 127) raw8 -= 256
    return raw8 as BigDecimal
}

private BigDecimal parseOutdoorTempHex(String hexStr) {
    if (hexStr.length() >= 4) {
        int raw16 = parseLittleEndianSigned16(hexStr.substring(0, 4))
        return raw16 / 10.0
    }
    int raw8 = Integer.parseInt(hexStr.substring(0, 2), 16)
    if (raw8 > 127) raw8 -= 256
    return raw8 as BigDecimal
}

private Integer formatTempForHub(BigDecimal tempC) {
    BigDecimal val = (location.temperatureScale == "F") ? celsiusToFahrenheit(tempC) : tempC
    return Math.round(val) as Integer
}

private String tempToDaikinHex(BigDecimal hubTemp, BigDecimal minC, BigDecimal maxC) {
    BigDecimal tempC = (location.temperatureScale == "F") ? fahrenheitToCelsius(hubTemp) : hubTemp
    BigDecimal clampedC = Math.max(minC, Math.min(maxC, tempC))
    int halfDegrees = (int) Math.round(clampedC * 2.0)
    return String.format("%02X", halfDegrees)
}

private void sendEventIfChanged(String name, value, String unit = null) {
    if (value == null) return
    def current = device.currentValue(name)
    if (current?.toString() != value.toString()) {
        Map evt = [name: name, value: value]
        if (unit) evt.unit = unit
        sendEvent(evt)
    }
}