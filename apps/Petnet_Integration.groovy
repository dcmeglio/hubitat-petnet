/**
 *  Petnet Integration
 *
 *  Copyright 2019 Dominick Meglio
 *
 */

import groovy.transform.Field

definition(
    name: "Petnet Integration",
    namespace: "dcm.petnet",
    author: "Dominick Meglio",
    description: "Connects to Petnet SmartFeeders",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")


preferences {
	page(name: "prefAccount", title: "Petnet")
	page(name: "prefMessages", title: "", install: false, uninstall: false, nextPage: "prefAccount")
}

def prefAccount() {
	return dynamicPage(name: "prefAccount", title: "Connect to Petnet", nextPage:"prefListDevices", uninstall:true, install: true) {
		section("App Name"){
			label title: "Enter a name for this app (optional)", required: false
		}
		section("Account Information"){
			input("pnUsername", "text", title: "Petnet Username", description: "Petnet Username")
			input("pnPassword", "password", title: "Petnet Password", description: "Petnet Password")
		}
		section("Settings") {
			input("enableNotifications", "bool", title: "Enable notifications?",defaultValue: false, displayDuringSetup: true, submitOnChange: true)
			if (enableNotifications)
			{
				input("notificationDevices", "capability.notification", title: "Device(s) to notify?", required: true, multiple: true)
				input("feedingNotification", "bool", title: "Notify when the feeder runs?", defaultValue: false, displayDuringSetup: true)
				input("lowHopperNotification", "bool", title: "Notify when the hopper is low?", defaultValue: false, displayDuringSetup: true, submitOnChange: true)
				if (lowHopperNotification)
				{
					input("hopperLevelAmount", "decimal", title: "Notify when the hopper level drops below this amount (in cups)")
				}
				href "prefMessages", title: "Change default notification messages", description: ""
			}
            input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
		}
	}
}

def prefMessages() {
    dynamicPage(name: "prefMessages", title: "", nextPage: "prefAccount", install: false, uninstall: false) {
        section("Notification Messages:") {
            paragraph "<b><u>Instructions to use variables:</u></b>"
            paragraph "%device% = Pet feeder device's name<br><hr>"
            input "messageFeedingOccurred", "text", title: "Feeding Occurred:", required: false, defaultValue:"%device% has completed a feeding", submitOnChange: true 
            input "messageHopperLow", "text", title: "Hopper Low:", required: false, defaultValue:"%device% is low on food", submitOnChange: true 
        }
    }
}

@Field appToken = "3Ei86ExIh6USKcuMJMrPgg=="
@Field apiUrl = "https://m-api570.petnet.io"
@Field appVersion = "5.7.4"


def installed() {
	logDebug "Installed with settings: ${settings}"

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
    
    authorize()
    
    getDevices()
}

def authorize() 
{
    def result = sendApiRequest("/tokens", "GET", null)
    logDebug "tokens result: ${result.data}"
    if (result.data.status == "ok")
    {
        state.firebaseUrl = result.data.tokens.firebase.url
        state.firebaseToken = result.data.tokens.firebase.token
        logDebug "got firebase info"
        result = sendApiRequest("/session", "POST", [
            user: [
                email: pnUsername,
                password: pnPassword
            ]
        ])
        logDebug "session result: ${result.data}"
        if (result.data.success == true)
        {
            state.apiToken = result.data.api_token
            state.apiCookie = result?.headers?.'Set-Cookie'?.split(';')?.getAt(0)
        }
    }
}

def getDevices()
{
    def result = sendApiRequest("/user/devices", "GET", null)
    
    logDebug "got devices ${result.data}"
    if (result.data.status == "ok")
    {
        for (def i = 0; i < result.data.devices.size(); i++)
        {
            def deviceId = result.data.devices[i]["device"]["id"]
            def petFeeder = getChildDevice("petnet:" + deviceId)
            if (!petFeeder)
            {    
                petFeeder = addChildDevice("petnet", "Petnet Feeder", "petnet:" + deviceId, 1234, ["name": "Petnet Feeder ${i+1}", isComponent: false])
            }
            petFeeder.updateDataValue("status_url", result.data.devices[i]["device"]["status_url"])
        }
    }
}

def sendApiRequest(path, method, body)
{
    def params = [
		uri: "https://${apiUrl}",
        path: "${path}",
		contentType: "application/json",
        requestContentType: "application/json",
        headers: [
            "Accept": "application/json;version=${appVersion}"
        ],
		query: [
                app_token: appToken
            ]
	]
    if (state.apiToken != null)
        params.query["api_token"] = state.apiToken
    
    if (state.apiCookie != null)
        params.headers["Cookie"] = state.apiCookie
    
    if (body != null)
        params.body = body
    
    def result = null
    if (method == "GET") {
        httpGet(params) { resp ->
            result = resp
        }
    }
    else if (method == "POST") {
        httpPost(params) { resp ->
            result = resp
        }
    }
    return result
}

def getFirebaseToken() {
    return state.firebaseToken;
}

def getFirebaseUrl() {
    return state.firebaseUrl;
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

def setHopperLevel(device, level) {
	if (enableNotifications) {
		if (feedingNotification) {
			if (level < state.previousHopperLevel)
			{
				notificationDevices.deviceNotification(messageFeedingOccurred.replace("%device%",device.getName()))
			}
		}
		if (lowHopperNotification) {
			if (state.previousHopperLevel >= hopperLevelAmount && level < hopperLevelAmount) {
				notificationDevices.deviceNotification(messageHopperLow.replace("%device%",device.getName()))
			}
		}
	}
	state.previousHopperLevel = level
}