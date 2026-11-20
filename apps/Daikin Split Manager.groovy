/**
 *  Daiken Split Manager (App)
 *
 *  Ported from Apoc182/local_daikin (DSIOT /dsiot/multireq local JSON API)
 *  Manages one or more local Daikin split indoor units on Hubitat Elevation
 *  with dynamic active/idle polling and Hubitat mode restrictions.
 */

definition(
    name: "Daiken Split Manager",
    namespace: "kurtsanders",
    author: "kurt@kurtsanders.com",
    description: "Connects and manages local Daikin split AC units using the /dsiot/multireq JSON API.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "addDevicePage")
    page(name: "verifyDevicePage")
    page(name: "editDevicePage")
    page(name: "saveEditPage")
}

def mainPage() {
    // Clear previous add-form inputs so adding multiple splits starts clean
    app.removeSetting("newDeviceName")
    app.removeSetting("newDeviceIp")

    dynamicPage(name: "mainPage", title: "Daiken Split Manager", install: true, uninstall: true) {
        section("Configured Daikin Split Units") {
            def children = getChildDevices()?.sort { it.displayName }
            if (children) {
                children.each { child ->
                    String ip = child.getDataValue("ipAddress") ?: "Unknown IP"
                    String mac = child.getDataValue("macAddress") ?: ""
                    String sw = child.currentValue("switch") ?: "off"
                    String mode = child.currentValue("thermostatMode") ?: "unknown"
                    String temp = child.currentValue("temperature") ?: "--"
                    href(
                        name: "edit_${child.deviceNetworkId}",
                        page: "editDevicePage",
                        params: [dni: child.deviceNetworkId],
                        title: "${child.displayName} (${ip}) [${sw.toUpperCase()}]",
                        description: "Mode: ${mode} | Indoor: ${temp}°${location.temperatureScale}${mac ? " | MAC: ${mac}" : ""} (Tap to rename or change IP)"
                    )
                }
            } else {
                paragraph "No Daikin split units added yet."
            }

            href(
                name: "toAddDevice",
                page: "addDevicePage",
                title: "+ Add a Daikin Split Unit",
                description: "Enter a custom device name and static IPv4 address"
            )
        }

        section("Dynamic Polling & Hubitat Mode Restrictions") {
            input name: "activePollInterval", type: "enum",
                title: "Active Polling Frequency (when any Daikin unit switch is ON)",
                options: [
                    "15s": "Every 15 Seconds",
                    "30s": "Every 30 Seconds",
                    "1m" : "Every 1 Minute",
                    "2m" : "Every 2 Minutes",
                    "5m" : "Every 5 Minutes"
                ],
                defaultValue: "30s", required: true

            input name: "pollInterval", type: "enum",
                title: "Default / Idle Polling Frequency (when all Daikin unit switches are OFF)",
                options: [
                    "1m" : "Every 1 Minute",
                    "2m" : "Every 2 Minutes",
                    "5m" : "Every 5 Minutes",
                    "10m": "Every 10 Minutes",
                    "15m": "Every 15 Minutes",
                    "30m": "Every 30 Minutes",
                    "0"  : "Disabled (Manual Refresh Only)"
                ],
                defaultValue: "5m", required: true

            input name: "pollModes", type: "mode",
                title: "Only poll during these Hubitat Mode(s) (leave blank to poll in all modes)",
                multiple: true, required: false

            boolean anyOn = isAnyUnitOn()
            String currentTier = anyOn ? "ACTIVE (${settings.activePollInterval ?: '30s'})" : "IDLE / DEFAULT (${settings.pollInterval ?: '5m'})"
            if (settings.pollModes) {
                boolean modeAllowed = (location.mode in settings.pollModes)
                paragraph "Current Hubitat Mode is <b>${location.mode}</b> — polling is currently <b>${modeAllowed ? currentTier : 'PAUSED (Mode Restricted)'}</b>."
            } else {
                paragraph "Current polling tier: <b>${currentTier}</b>."
            }
        }

        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging (auto-disables after 30 minutes)", defaultValue: false
        }
    }
}

// ============================================================================
// Add New Daikin Split Page
// ============================================================================

def addDevicePage() {
    dynamicPage(name: "addDevicePage", title: "Add a Daikin Split Unit", nextPage: "verifyDevicePage") {
        section("Unit Name & IP Address") {
            input name: "newDeviceName", type: "text", title: "Child Device Name (e.g., Sunroom Split, Master Bedroom Split)",
                required: true
            input name: "newDeviceIp", type: "text", title: "Static IPv4 Address (e.g., 10.0.0.136)",
                required: true
        }
    }
}

def verifyDevicePage() {
    dynamicPage(name: "verifyDevicePage", title: "Verify & Create Device", nextPage: "mainPage") {
        section("DSIOT Endpoint Verification") {
            String ip = settings.newDeviceIp?.trim()
            String devName = settings.newDeviceName?.trim() ?: "Daikin Split"
            Map result = probeDaikinUnit(ip)

            if (result.success) {
                String dni = "daikin-dsiot-${result.mac ?: ip.replace('.', '-')}"
                def existing = getChildDevice(dni)
                if (!existing) {
                    def child = addChildDevice("kurtsanders", "Daiken Split System", dni, [
                        name: devName,
                        label: devName,
                        isComponent: false
                    ])
                    child.updateDataValue("ipAddress", ip)
                    child.updateDataValue("macAddress", result.mac ?: "")
                    child.updateDataValue("firmware", result.ver ?: "")
                    subscribe(child, "switch", "childSwitchHandler")
                    child.refresh()
                    paragraph "<b>Success!</b> Verified DSIOT endpoint at <b>${ip}</b> (MAC: ${result.mac ?: 'N/A'}, Firmware: ${result.ver ?: 'N/A'}).<br>Created child device: <b>${devName}</b>."
                } else {
                    existing.setLabel(devName)
                    existing.setName(devName)
                    existing.updateDataValue("ipAddress", ip)
                    subscribe(existing, "switch", "childSwitchHandler")
                    existing.refresh()
                    paragraph "<b>Updated!</b> A device for MAC ${result.mac} already existed and was updated to <b>${devName}</b> at IP <b>${ip}</b>."
                }
            } else {
                paragraph "<b>Connection Failed:</b> Could not verify /dsiot/multireq at <b>${ip ?: 'blank IP'}</b>.<br>Error: ${result.error}<br><br>Click <b>Previous</b> to check the IP address and try again."
            }
        }
    }
}

// ============================================================================
// Edit / Rename Existing Child Device Page
// ============================================================================

def editDevicePage(Map params) {
    String dni = params?.dni ?: state.editingDni
    state.editingDni = dni
    def child = getChildDevice(dni)

    dynamicPage(name: "editDevicePage", title: "Edit Daikin Split Unit", nextPage: "saveEditPage") {
        if (child) {
            section("Modify Child Device Settings") {
                input name: "editDeviceName", type: "text", title: "Child Device Name",
                    defaultValue: child.displayName, required: true
                input name: "editDeviceIp", type: "text", title: "Static IPv4 Address",
                    defaultValue: child.getDataValue("ipAddress"), required: true
            }
        } else {
            section {
                paragraph "Device not found."
            }
        }
    }
}

def saveEditPage() {
    dynamicPage(name: "saveEditPage", title: "Device Updated", nextPage: "mainPage") {
        section {
            def child = getChildDevice(state.editingDni)
            if (child) {
                String newName = settings.editDeviceName?.trim() ?: child.displayName
                String newIp = settings.editDeviceIp?.trim() ?: child.getDataValue("ipAddress")
                child.setLabel(newName)
                child.setName(newName)
                child.updateDataValue("ipAddress", newIp)
                child.refresh()
                paragraph "Updated <b>${newName}</b> with IP address <b>${newIp}</b>."
            }
            app.removeSetting("editDeviceName")
            app.removeSetting("editDeviceIp")
            state.remove("editingDni")
        }
    }
}

// ============================================================================
// Single-Unit Probe & Dynamic Polling Logic
// ============================================================================

private Map probeDaikinUnit(String ip) {
    Map out = [success: false, mac: "", ver: "", error: ""]
    if (!ip) {
        out.error = "No IP address provided."
        return out
    }

    try {
        httpGet([uri: "http://${ip}:80/common/basic_info", timeout: 5]) { resp ->
            if (resp.status == 200) {
                String body = resp.data?.toString() ?: ""
                body.split(",").each { pair ->
                    def kv = pair.split("=", 2)
                    if (kv.size() == 2) {
                        if (kv[0] == "mac") out.mac = kv[1].trim()
                        if (kv[0] == "ver") out.ver = kv[1].trim()
                    }
                }
            }
        }
    } catch (Exception e) {
        if (logEnable) log.debug "basic_info optional check failed for ${ip}: ${e.message}"
    }

    try {
        Map bodyMap = [requests: [[op: 2, to: "/dsiot/edge/adr_0100.dgc_status?filter=pv"]]]
        httpPostJson([uri: "http://${ip}:80/dsiot/multireq", body: bodyMap, timeout: 6]) { resp ->
            if (resp.status == 200 && resp.data?.responses?.getAt(0)?.rsc == 2000) {
                out.success = true
            } else {
                out.error = "Unexpected DSIOT response code: ${resp.data?.responses?.getAt(0)?.rsc}"
            }
        }
    } catch (Exception e) {
        out.error = e.message
    }

    return out
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    unsubscribe()
    initialize()
}

def initialize() {
    if (logEnable) {
        log.debug "Debug logging enabled — will automatically turn off in 30 minutes."
        runIn(1800, "logsOff")
    }

    subscribe(location, "mode", "modeChangeHandler")
    getChildDevices().each { child ->
        subscribe(child, "switch", "childSwitchHandler")
    }

    updatePollingSchedule()
    pollAllChildren()
}

def logsOff() {
    log.warn "Debug logging disabled automatically after 30 minutes."
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private boolean isAnyUnitOn() {
    return getChildDevices().any { it.currentValue("switch") == "on" }
}

def childSwitchHandler(evt) {
    if (logEnable) log.debug "Child device '${evt.displayName}' switch changed to '${evt.value}' — re-evaluating polling schedule."
    updatePollingSchedule()
}

private void updatePollingSchedule() {
    unschedule("pollAllChildren")

    boolean anyOn = isAnyUnitOn()
    String intervalCode = anyOn ? (settings.activePollInterval ?: "30s") : (settings.pollInterval ?: "5m")

    if (logEnable) {
        log.debug "Setting Daikin polling schedule to ${anyOn ? 'ACTIVE' : 'IDLE'} interval: ${intervalCode}"
    }

    switch (intervalCode) {
        case "0":
            if (logEnable) log.debug "Idle polling is disabled (Manual Refresh Only)."
            break
        case "15s":
            schedule("0/15 * * * * ?", "pollAllChildren")
            break
        case "30s":
            schedule("0/30 * * * * ?", "pollAllChildren")
            break
        case "1m":
        case "1":
            runEvery1Minute("pollAllChildren")
            break
        case "2m":
        case "2":
            schedule("0 */2 * * * ?", "pollAllChildren")
            break
        case "5m":
        case "5":
            runEvery5Minutes("pollAllChildren")
            break
        case "10m":
        case "10":
            runEvery10Minutes("pollAllChildren")
            break
        case "15m":
        case "15":
            runEvery15Minutes("pollAllChildren")
            break
        case "30m":
        case "30":
            runEvery30Minutes("pollAllChildren")
            break
        default:
            runEvery5Minutes("pollAllChildren")
            break
    }
}

def modeChangeHandler(evt) {
    if (!settings.pollModes || (evt.value in settings.pollModes)) {
        if (logEnable) log.debug "Hubitat mode changed to '${evt.value}' (allowed mode) — polling Daikin units now."
        pollAllChildren(true)
    } else if (logEnable) {
        log.debug "Hubitat mode changed to '${evt.value}' — outside allowed polling modes ${settings.pollModes}."
    }
}

def pollAllChildren(boolean force = false) {
    if (!force && settings.pollModes && !(location.mode in settings.pollModes)) {
        if (logEnable) log.debug "Skipping scheduled Daikin poll: current mode '${location.mode}' is not in ${settings.pollModes}."
        return
    }

    getChildDevices().each { child ->
        if (logEnable) log.debug "Polling ${child.displayName} (${child.getDataValue('ipAddress')})"
        child.refresh()
    }
}