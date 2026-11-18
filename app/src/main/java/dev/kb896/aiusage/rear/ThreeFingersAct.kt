package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.ThreeFingers

object ThreeFingersAct {
    interface Does {
        fun screenshot()
        fun recording()
        fun backOff()
    }

    /** Runs what [choice] asks for, if [backOurs]. Returns what was done, for the one log line. */
    fun run(choice: ThreeFingers, backOurs: Boolean, does: Does): String {
        if (!backOurs) return "nothing, the back is not lit or not ours"
        return when (choice) {
            ThreeFingers.SCREENSHOT -> { does.screenshot(); "a screenshot of the back" }
            ThreeFingers.RECORDING -> { does.recording(); "the recording of the back started or stopped" }
            ThreeFingers.BACK_OFF -> { does.backOff(); "the back off" }
            ThreeFingers.NOTHING -> "nothing, as set"
        }
    }
}
