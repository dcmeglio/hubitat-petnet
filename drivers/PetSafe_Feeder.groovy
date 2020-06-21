/**
 *  PetSafe Feeder
 *
 *  Copyright 2020 Dominick Meglio
 *
 */
metadata {
    definition (name: "PetSafe Feeder", namespace: "dcm.petsafe", author: "Dominick Meglio") {
		capability "Momentary"
		capability "PushableButton"
        capability "Battery"
        capability "PowerSource"
        capability "Consumable"

        attribute "hopperStatus", "enum"
    }
}

preferences {
    input "feedAmount", "enum", title: "Feeding Amount (in Cups)" , options: [1:"1/8", 2:"1/4", 3: "3/8", 4: "1/2", 5: "5/8", 6: "3/4", 7: "7/8", 8: "1"]
}
def installed() {
	sendEvent(name: "numberOfButtons", value: "1")
}

def push() {
    parent.handleFeed(device, feedAmount)
}