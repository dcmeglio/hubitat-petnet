/**
 *  Petsafe Integration
 *
 *  Copyright 2019 Dominick Meglio
 *
 */

import groovy.transform.Field

definition(
    name: "Petsafe Integration",
    namespace: "dcm.petsafe",
    author: "Dominick Meglio",
    description: "Connects to Petsafe SmartFeeders",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	documentationLink: "https://github.com/dcmeglio/hubitat-petnet/blob/master/README.md")


preferences {
    page(name: "prefMain", title: "Petsafe")
	page(name: "prefAccount", title: "Petsafe")
    page(name: "prefCode", title: "Petsafe Code")
    page(name: "prefDevices", title: "Devices")
	page(name: "prefMessages", title: "", install: false, uninstall: false, nextPage: "prefAccount")
}

def prefMain() {
    def nextPage = "prefDevices"
    if (petsafeEmail == null)
        nextPage = "prefAccount"
	return dynamicPage(name: "prefMain", title: "Petsafe", nextPage:nextPage, uninstall:false, install: false) {
		section("App Name"){
			label title: "Enter a name for this app (optional)", required: false
		}
        if (petsafeEmail != null) {
            section {
                href(name: "prefAccount", title: "Account Information", required: false, page: "prefAccount", description: "Update your account information")
            }
        }
    }
}

def prefAccount() {
	return dynamicPage(name: "prefAccount", title: "Connect to Petsafe", nextPage:"prefCode", uninstall:false, install: false) {
		section {
			input "petsafeEmail", "email", title: "Petsafe Email", description: "Petsafe Email"
		}
    }
}

def prefCode() {
    apiAuthenticate()
    return dynamicPage(name :"prefCode", title: "Code", nextPage: "prefDevices", uninstall: false, install: false) {
		section {
           paragraph "In a minute you should receive an email with a 6 digit code. Enter the code below including the hyphen."
           input "petsafeCode", "string", title: "PIN code", description: "Petsafe PIN code"
		}
	}
}

def prefDevices() {
    apiGetTokens()
    def feeders = apiGetFeeders()

    state.feeders = [:]
    for (feeder in feeders) {
        state.feeders[feeder.thing_name] = feeder.settings.friendly_name
    }
    return dynamicPage(name :"prefDevices", title: "Devices", nextPage: "prefMessages", uninstall: false, install: false) {
		section {
           input "smartfeeders", "enum", title: "Smart Feeders", options: state.feeders, multiple: true
		}
	} 
}

def prefMessages() {
    dynamicPage(name: "prefMessages", title: "", install: true, uninstall: true) {
        section {
            input "enableNotifications", "bool", title: "Enable notifications?",defaultValue: false, displayDuringSetup: true, submitOnChange: true
        }
        if (enableNotifications)
        {
            section {
                input "notificationDevices", "capability.notification", title: "Device(s) to notify?", required: true, multiple: true
            }
            section("Notification Messages:") {
                paragraph "<b><u>Instructions to use variables:</u></b>"
                paragraph "%device% = Pet feeder device's name<br><hr>"
                input "messageFeedingOccurred", "text", title: "Feeding Occurred:", required: false, defaultValue:"%device% has completed a feeding", submitOnChange: true 
                input "messageHopperLow", "text", title: "Hopper Low:", required: false, defaultValue:"%device% is low on food", submitOnChange: true 
                input "messageHopperEmpty", "text", title: "Hopper Empty:", required: false, defaultValue:"%device% is empty", submitOnChange: true 
                input "messageErrorOccurred", "text", title: "Error Occurred:", required: false, defaultValue: "An error occurred feeding from %device%", submitOnChange: true 
            }
        }
    }
}

@Field apiUrl = "https://api.ps-smartfeed.cloud.petsafe.net/api/v2/"
@Field apiUserUrl = "https://users-api.ps-smartfeed.cloud.petsafe.net/users/"

def installed() {
	logDebug "Installed with settings: ${settings}"
    state.lastMessageCheck = new Date()
	initialize()
}

def updated() {
	logDebug "Updated with settings: ${settings}"
    unschedule()
	unsubscribe()
	initialize()
}

def uninstalled() {
	logDebug "Uninstalled app"

	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}	
}

def initialize() {
	logDebug "initializing"

    for (feeder in smartfeeders) {
        if (!getChildDevice("petsafe:" + feeder)) {
            addChildDevice("dcm.petsafe", "PetSafe Feeder", "petsafe:" + feeder, 1234, ["name": state.feeders[feeder], isComponent: false])
        }
    }

    schedule("0 */1 * * * ? *", refreshDevices)
}

def refreshDevices() {
    def feederData = apiGetFeeders()
    for (feeder in feederData) {
        def childFeeder = getChildDevice("petsafe:" + feeder.thing_name)
        if (childFeeder != null) {
            if (feeder.is_adapter_installed)
                childFeeder.sendEvent(name: "powerSource", value: "dc")
            if (feeder.is_batteries_installed) {
                if (!feeder.is_adapter_installed)
                    childFeeder.sendEvent(name: "powerSource", value: "battery")
                
                def voltage = feeder.battery_voltage.toInteger()
                if (voltage >= 100) {
                    if (voltage > 29100)
                        childFeeder.sendEvent(name: "battery", value: 100)
                    else {
                        childFeeder.sendEvent(name: "battery", value: (int)((100 * (voltage - 26000))/(29100-2600)))
                    }
                }
            }

            if (feeder.is_food_low == 0) {
                childFeeder.sendEvent(name: "hopperStatus", value: "full")
                childFeeder.sendEvent(name: "consumableStatus", value: "good")
            }
            else if (feeder.is_food_low == 1) {
                childFeeder.sendEvent(name: "hopperStatus", value: "low")
                childFeeder.sendEvent(name: "consumableStatus", value: "replace")
                notifyFoodLow(childFeeder)
            }
            else if (feeder.is_food_low == 2) {
                childFeeder.sendEvent(name: "hopperStatus", value: "empty")
                childFeeder.sendEvent(name: "consumableStatus", value: "missing")
                notifyFoodEmpty(childFeeder)
            }
            def recentFeedings = apiGetRecentFeedings(feeder.thing_name)
            for (feeding in recentFeedings) {
                def msgTime = Date.parse("yyyy-MM-dd HH:mm:ss", feeding.created_at, TimeZone.getTimeZone('UTC'))
                                
                if (msgTime > timeToday(state.lastMessageCheck, TimeZone.getTimeZone('UTC'))) {
                    switch (feeding.message_type) {
                        case "FEED_ERROR_MOTOR_CURRENT":
                        case "FEED_ERROR_MOTOR_SWITCH":
                            childFeeder.sendEvent(name: "consumableStatus", value: "maintenance_required")
                            notifyError(childFeeder)
                            break
                        case "FEED_DONE":
                            notifyFeeding(childFeeder)
                            break
                        case "FOOD_GOOD":
                        case "FOOD_LOW":
                        case "FOOD_EMPTY":                          
                            break
                        case "WILL_MESSAGE":
                            log.info "WILL_MESSAGE received, unsure what this is ${feeding}"
                            break
                    }
                }
            }
            state.lastMessageCheck = new Date()
        }
    }
}

def handleFeed(device, feedAmount) {
    def feeder = device.deviceNetworkId.replace("petsafe:","")
    def params = [
		uri: "${apiUrl}feeders/${feeder}/meals",
		contentType: "application/json",
        requestContentType: "application/json",
        headers: [
            "token": state.deprecatedToken
        ],
		body: [
            "amount": feedAmount
        ]
	] 
    def result = null

    httpPost(params) { resp ->
        result = resp.data
    }
    return result
}

def notifyError(dev) {
    if (notificationDevices != null)
        notificationDevices*.deviceNotification(messageErrorOccurred.replace("%device%",dev.displayName))
}

def notifyFeeding(dev) {
    if (notificationDevices != null)
        notificationDevices*.deviceNotification(messageFeedingOccurred.replace("%device%",dev.displayName))
}

def notifyFoodLow(dev) {
    if (notificationDevices != null)
        notificationDevices*.deviceNotification(messageHopperLow.replace("%device%",dev.displayName))
}

def notifyFoodEmpty(dev) {
    if (notificationDevices != null)
        notificationDevices*.deviceNotification(messageHopperEmpty.replace("%device%",dev.displayName))
}

def apiAuthenticate() {
    def params = [
		uri: "${apiUserUrl}",
		contentType: "application/json",
        requestContentType: "application/json",
		body: [
            "consentVersion": "2019-06-25",
            "email": petsafeEmail,
            "language": "en"
        ]
	] 
    def result = null

    httpPost(params) { resp ->
        result = resp.data
    }
    return result
}

def apiGetTokens() {
    def code = petSafeCode
    if (!(petsafeCode =~ /\d{3}-\d{3}/))
        code = petsafeCode[0..2] + "-" + petsafeCode[3..5]

        log.debug code
       def params = [
		uri: "${apiUserUrl}tokens",
		contentType: "application/json",
        requestContentType: "application/json",
		body: [
            "code": code,
            "email": petsafeEmail
        ]
	] 
    def result = null
    
    httpPost(params) { resp ->
        result = resp.data
        state.identityId = result.identityId
        state.accessToken = result.accessToken
        state.refreshToken = result.refreshToken
        state.deprecatedToken = result.deprecatedToken
        app.removeSetting("petsafeCode")
    }
    return result 
}

def apiGetFeeders() {
    def params = [
		uri: "${apiUrl}feeders",
		contentType: "application/json",
        requestContentType: "application/json",
        headers: [
            "token": state.deprecatedToken
        ]
    ]

    def result = null
    httpGet(params) { resp ->
        result = resp.data
    }
    return result
}

def apiGetRecentFeedings(feeder) {
        def params = [
		uri: "${apiUrl}feeders/${feeder}/messages?days=2",
		contentType: "application/json",
        requestContentType: "application/json",
        headers: [
            "token": state.deprecatedToken
        ]
    ]

    def result = null
    httpGet(params) { resp ->
        result = resp.data
    }
    return result
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}