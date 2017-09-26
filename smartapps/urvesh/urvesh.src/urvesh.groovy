import groovy.json.*
import groovy.json.JsonSlurper
import org.json.JSONObject
import static java.util.Calendar.*
import java.text.SimpleDateFormat

definition(
        name: "urvesh",
        namespace: "urvesh",
        author: "Peter Yu",
        description: "urvesh testing app",
        category: "",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

preferences {
    page(name: "caregiverPage", title: "Enter Caregiver Information", nextPage: "memberPage", install: false) {
        section {
            input "caregiverEmail", "email", required: true, title: "Enter Caregiver email address"
            input "caregiverFirstName", "firstName", required: true, title: "Enter the Caregiver's First Name"
            input "caregiverLastName", "lastName", required: true, title: "Enter the Caregiver's Last Name"
            input("phone", "phone", title: "Enter the Caregiver's phone number", description: "Phone Number", multiple: true, required: true)
        }
    }
    page(name: "memberPage", title: "Enter Member Information", nextPage: "sensorPage", install: false) {
        section {
            input "memberEmail", "email", required: true, title: "Enter member email address"
            input "memberFirstName", "firstName", required: true, title: "Enter the member's First Name"
            input "memberLastName", "lastName", required: true, title: "Enter the member's Last Name"
            input "memberAge", "number", required: true, title: "Enter the member's Age"

        }
    }
    page(name: "sensorPage", title: "Where to Monitor", uninstall: true, install: true) {
        section {
            input("sensors", "capability.motionSensor", required: true, title: "Which Room?", multiple: true)
        }
    }
}

def installed() {
    log.trace "inside installed"
    initialize()
}

def updated() {
    log.trace "inside updated"
    unsubscribe()
    initialize()
}

def initialize() {
    log.trace "inside initialize"
    state.ngrok = "http://4f03eb1e.ngrok.io"
    log.trace "ngrok url = ${state.ngrok}"

    def oneHour = 60 * 60 * 1000
    state.noMovementIntervals = [
            base : 3 * oneHour,
            afterWakeUp : oneHour
    ]
    state.preferences = [
            wakeUpTime : 8 * oneHour,
            bedTime : 22 * oneHour
    ]
    state.isMemberAsleep = true
    //state.bedFlag = false
    memberRegister()
    subscribe(sensors, "motion.active", motionHandler)
}


def memberRegister() {
    log.trace "inside memberRegister"
    def sensorList = []
    sensors.eachWithIndex { name, index ->
        def map = [sensorName: sensors[index].displayName, sensorId: sensors[index].id]
        sensorList << map
    }
    def phoneLength = phone.length()
    if (phoneLength != 10) {
        log.trace "phone length is not 10, sending out two notifications"
        sendPush "Please Enter Your 10 Digit Phone Number"
        //TODO: Figure out phone number validation
    }

    log.trace "setting up member register params object"
    def params = [
            uri : "${state.ngrok}/motion/api/v1.0/caregivers",
            body: [
                    caregiverEmail    : caregiverEmail,
                    caregiverFirstName: caregiverFirstName,
                    caregiverLastName : caregiverLastName,
                    phoneNumbers      : [
                            name: "${caregiverFirstName} ${caregiverLastName}",
                            number: phone
                    ],
                    //TODO: MEMBER MAP AND CAREGIVER MAP
                    memberId          : memberEmail,
                    memberFirstName   : memberFirstName,
                    memberLastName    : memberLastName,
                    age               : memberAge,
                    hubId             : sensors[0].hub.id,
                    sensorList        : sensorList
            ]
    ]
    log.trace "sending http post with data = ${params}"

    try {
        httpPostJson(params)
    } catch (e) {
        log.error "***member registration failed: $e***\n"
        log.debug param
        sendPush "Failed to create member"
    }
}

def motionHandler(evt) {
    log.trace "indside motionHandler"

    def hourFormat = state.noMovementIntervals.base/1000/60/60
    log.trace "houtFormat = ${hourFormat}"

    //runIn(state.noMovementIntervals.base, alert, [data : [message : "it's been  hour since movement was detected"]])
	runIn(60, alert, [data : [message : "it's been  hour since movement was detected"]])

    Date date = new Date()
    String dateFormat = date.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    def tempValue = sensors.currentValue("temperature")
    def avgTemp = tempValue.sum() / tempValue.size()

    log.trace "got temperature ${avgTemp}"
    def params = [
            uri : "${state.ngrok}/motion/api/v1.0/movements",
            body: [
                    memberId   : memberEmail,
                    sensorId   : evt.deviceId,
                    temperature: avgTemp,
                    timestamp  : dateFormat
            ]
    ]
    log.trace "built params for movement ${params}"

    try {
        log.trace "posting params"
        httpPostJson(params) { resp ->
            //def mapAsString = resp.data.toString()
            //def slurper = new groovy.json.JsonSlurper()
           // def result = slurper.parseText(mapAsString)
            //log.debug "movement response is ${result}"
            //null check on the pref
            //TODO: NUL CHECK
            if(resp.data != null){
                log.debug "response data is ${resp.data}"
                //state.noMovementInterval = (resp.data.preference.alertInterval) ? resp.data.preference.alertInterval : state.noMovementInterval
                //runin(state.noMovementInterval, wakeMotion) //change it to wake up interval
                //state.bed = (resp.data.preference) ? resp.data.preference.bedTime : state.bed
                //log.trace "wake up time and bed time: ${state.preference.wakeUpTime} ${state.preference.bedTime}"
                //log.trace state.noMovementInterval + '*' + state.wakeUp + '*' + state.bed
                if (resp.data.redZone) {
                    def message = "Motion was detected in red zone: ${resp.data.roomName}"
                    def resparams = [
                            uri : "${state.ngrok}/motion/api/v1.0/sendMessage",
                            body: [
                                    memberId : memberEmail,
                                    message  : message.toString(),
                                    types    : ["text","alexa"],
                                    roomName : resp.data.roomName,
                                    roomId : resp.data.roomId
                            ]
                    ]

                    log.trace "building params for alerts ${params}"
                    try {
                        httpPostJson(resparams)
                    } catch (e) {
                        log.error "***something went wrong with sending red zone call: $e***\n"
                    }
                }
            }
        }

    } catch (e) {
        log.error "***something went wrong with sendMotion(): $e***\n"
    }

    wakeAlert()
}

def alert(data) {
    log.trace "in alert"
    def params = [
            uri : state.ngrok + "/motion/api/v1.0/sendMessage",
            body: [
                    memberId : memberEmail,
                    message  : data.message,
                    types    : ["text"]
            ]
    ]

    log.trace "building params for alerts ${params}"
    try {
        httpPostJson(params) { resp ->
        }
    } catch (e) {
        log.error "***something went wrong with alert(): $e***\n"
    }
}

def wakeMotion() {
    log.trace "inside wake up motion"
    if (!state.wakeAlert) {
        log.trace "state.wakeAlert is false ?? ${state.wakeAlert}"
        alert("test")
    }
}

def wakeAlert() {
    log.trace"inside wake alert"
    def bedTimeHour = state.preferences.bedTime / (60 * 60 * 1000)
    bedTimeHour = (bedTimeHour >= 10 && bedTimeHour < 24) ? bedTimeHour.toString() : '0' + bedTimeHour.toString()
    def bedTimeMin = state.preferences.bedTime % (60 * 60 * 1000)
    bedTimeMin = (bedTimeMin >= 10 && bedTimeMin < 60) ? bedTimeMin.toString() : '0' + bedTimeMin.toString()
    def testTime1 = bedTimeHour + ':' + bedTimeMin
    def bedTimeHour2 = (state.preferences.bedTime + 60 * 60 * 1000 * 9) / (60 * 60 * 1000)
    bedTimeHour2 = (bedTimeHour2 >= 10 && bedTimeHour2 < 24) ? bedTimeHour2.toString() : '0' + bedTimeHour2.toString()
    def bedTimeMin2 = (state.preferences.bedTime + 60 * 60 * 1000 * 9) % (60 * 60 * 1000)
    bedTimeMin2 = (bedTimeMin2 >= 10 && bedTimeMin2 < 60) ? bedTimeMin2.toString() : '0' + bedTimeMin2.toString()
    def testTime2 = bedTimeHour2 + ':' + bedTimeMin2
    log.trace testTime1 + ' ' + testTime2
    def between = timeOfDayIsBetween(testTime1, testTime2, new Date(), location.timeZone)
    if (between) {
        log.trace "the time is in between";
        state.isMemberAsleep = false
    } else {
        log.debug 'the time is not in between'
    }
}
