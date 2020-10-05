package ul.co.jatra.statemachine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ul.co.jatra.statemachine.Event.*
import ul.co.jatra.statemachine.State.Companion.Terminal
import ul.co.jatra.statemachine.State.Companion.stateMachine


sealed class Event {
    override fun toString() = this.javaClass.simpleName ?: "Event"
    object AuthFailed: Event()
    object Authenticated : Event()
    object AcceptedTerms : Event()
    object AcceptedConditions : Event()
    object Finish : Event()
}


data class State(
    //For debug
    var name: String,
    //suspending function to execute on entry to this state
    var onEntry: (suspend () -> Unit)? = null,
    //suspending funtion to execute on exit
    var onExit: (suspend () -> Unit)? = null,
    //suspending funtion to handle any events that occur when in this state.
    var onEvent: (suspend (Event) -> State?)? = null) {

    suspend fun transition(event: Event): State {
        val nextState = onEvent?.let { it(event) }
        var state: State? = this
        if (nextState != state) {
            state?.onExit?.let { it() }
            state = nextState
            state?.onEntry?.let { it() }
        }
        return state ?: this
    }

    companion object {
        suspend fun stateMachine(startingState: State, channel: Channel<Event>) {
            var state = startingState
            while (state != Terminal) {
                state = state.transition(channel.receive())
                println("Current state: ${state.name}")
            }
        }
        val Terminal = State("terminal")
    }
}

class StateMachine(var startingState: State, var channel: Channel<Event>) {
    suspend fun start() {
        var state = startingState
        while (state != Terminal) {
            state = state.transition(channel.receive())
            println("Current state: ${state.name}")
        }
    }
    companion object {
        val Terminal = State("terminal")
    }
}

class StateMachineBuilder {
    private lateinit var startState: State
    private lateinit var channel: Channel<Event>

    fun startState(startState: State) {
        this.startState = startState
    }
    fun channel(channel: Channel<Event>) {
        this.channel = channel
    }

    fun build() = StateMachine(startState, channel)
}

class StateBuilder {
    private var name: String = ""
    private var onEntry: suspend () -> Unit = {}
    private var onExit: suspend () -> Unit = {}
    private var onEvent: suspend (Event) -> State? = { null }

    fun name(name: () -> String) {
        this.name = name()
    }

    fun onEntry(onEntry: suspend () -> Unit) {
        this.onEntry = onEntry
    }

    fun onExit(onExit: suspend () -> Unit) {
        this.onExit = onExit
    }

    //Return null for no action
    //Return this state to re-enter, and so re-run the onEntry
    //Return a different state to run 1/ the onExit, then the onEntry of next state.
    fun onEvent(onEvent: suspend (Event) -> State?) {
        this.onEvent = onEvent
    }

    fun build() = State(name, onEntry, onExit, onEvent)
}

fun state(lambda: StateBuilder.() -> Unit) =
    StateBuilder()
        .apply(lambda)
        .build()

fun stateMachine(lambda: StateMachineBuilder.() -> Unit) =
    StateMachineBuilder()
        .apply(lambda)
        .build()

suspend fun main() {
    lateinit var state1: State
    lateinit var state2: State

    state1 = state {
        name { "State1" }
        onEntry {
            println(">onEntry state1")
        }
        onExit {
            println(">onExit state1")
        }
        onEvent {
            println(">onEvent $it state1")
            //using a when means
            //  1/ compiler will warn if events are missed
            //  2/ all events have to be handled in the satte, even if they are not relevant
            when (it) {
                is AuthFailed -> state1
                is Authenticated -> state2
                is AcceptedTerms -> state2
                is AcceptedConditions -> state2
                else -> null
            }
        }
    }
    state2 = state {
        name { "State2" }
        onEntry {
            println(">onEntry state2")
            delay(200)
        }
        onExit {
            println(">onExit state2")
        }
        onEvent {
            println(">onEvent $it state2")
            when (it) {
                is AuthFailed -> state1
                is Finish -> Terminal
                else -> null
            }
        }
    }

    val startingState = state1

    val channel = Channel<Event>(UNLIMITED)


    println("Start state: ${startingState.name}")

    val eventLoop = CoroutineScope(Dispatchers.Default).launch {
        stateMachine(startingState, channel)
    }

    channel.offer(Authenticated)
    delay(1000)
    channel.offer(Authenticated)
    delay(1000)
    channel.offer(AcceptedTerms)
    delay(1000)
    channel.offer(AcceptedTerms)
    delay(1000)
    channel.offer(Finish)

    eventLoop.join()
}
