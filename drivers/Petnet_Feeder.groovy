/**
 *  Petnet Feeder
 *
 *  Copyright 2019 Dominick Meglio
 *
 */
metadata {
    definition (name: "Petnet Feeder", namespace: "petnet", author: "Dominick Meglio") {
		capability "Momentary"
		capability "PushableButton"
        capability "Initialize" 
		
		attribute "hopperLevel", "number"
    }
}

preferences {
    input "feedAmount", "enum", title: "Feeding Amount (in Cups)" , options: ["0.0625":"1/16", "0.125":"1/8", "0.1875":"3/16", "0.25":"1/4", "0.3333": "1/3", "0.375":"3/8", "0.4375":"7/16", "0.5":"1/2", "0.5625":"9/16", "0.6666":"2/3", "0.6875":"11/16", "0.75":"3/4", "0.8125":"13/16", "0.875":"7/8", "0.9375":"15/16", "1":"1" ]
    input "debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false
}
def installed() {
	sendEvent(name: "numberOfButtons", value: "1")
    runIn(5, initialize)
}

def initialize()
{
    log.debug getDataValue("status_url")+".json?auth="+parent.getFirebaseToken()
    eventStreamConnect(getDataValue("status_url")+".json?auth="+parent.getFirebaseToken(), "Bearer " + parent.getFirebaseToken())
}

def push() {
	if (feed())
        sendEvent(name: "pushed", value: 1,  isStateChange: true)
}

def parse(description) {
     def json = parseJson(description)
    
    if (json.path == "/server/hopper/last_filled")
    {
        sendEvent(name: "hopperLevel", value: json.data.cups_remaining)
    }
    else if (json.path == "/server")
    {
        sendEvent(name: "hopperLevel", value: json.data["hopper/cups_remaining"])
    }
    else if (json.path == "/device/realtime/feeding")
    {
        if (data.progress == 1)
            queryHopper()
    }
}

def queryHopper()
{
    def statusPath = getDataValue("status_url").replace(parent.getFirebaseUrl(),"") + "/server/hopper.json"
    def params = [
		uri: parent.getFirebaseUrl(),
        path: statusPath,
		contentType: "application/json",
        requestContentType: "application/json",
        query:
        [
            auth: parent.getFirebaseToken()
        ]
    ]
    def result = false
    try
    {
        httpGet(params) { resp -> 
            result = true
            sendEvent(name: "hopperLevel", value: resp.data.cups_remaining)
        }
    }
    catch (e)
    {
        log.error e
        result = false
    }
    return result
}

def feed()
{
    def feedPath = getDataValue("status_url").replace(parent.getFirebaseUrl(),"") + "/mobile/commands/feed.json"
    def params = [
		uri: parent.getFirebaseUrl(),
        path: feedPath,
		contentType: "application/json",
        requestContentType: "application/json",
        query:
        [
            auth: parent.getFirebaseToken()
        ],
        body: '{"portion": ' + feedAmount + "}"
    ]
    def result = false
    try
    {
        httpPut(params) { resp -> 
            result = true
        }
    }
    catch (e)
    {
        log.error e
        result = false
    }
    return result
}

def eventStreamStatus(String msg) {
	if(!msg.contains("ALIVE:")) 
    { 
        logDebug "Status: ${msg}" 
    }
	if (msg.contains("STOP:")) 
    {
        stopEventStream()
        runIn(10, reconnect)
	} 
    else if (msg.contains("ALIVE:")) 
    {
		state.streamRunning = true
	} 
    else if (msg.contains("ERROR:")) 
    {
        stopEventStream()
        runIn(10, reconnect)
    }
}

def reconnect() {
    initialize()
}

def stopEventStream() {
    state.streamRunning = false
    eventStreamClose()
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}