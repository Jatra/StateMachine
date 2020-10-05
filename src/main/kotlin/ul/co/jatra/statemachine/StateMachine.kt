package ul.co.jatra.statemachine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ul.co.jatra.statemachine.Event.*
import ul.co.jatra.statemachine.State.Companion.state
import ul.co.jatra.statemachine.StateMachine.Companion.stateMachine



data class State<E>(
    //For debug
    var name: String,
    //suspending function to execute on entry to this state
    var onEntry: (suspend () -> Unit)? = null,
    //suspending function to execute on exit
    var onExit: (suspend () -> Unit)? = null,
    //suspending function to handle any events that occur when in this state.
    var onEvent: (suspend (Event) -> State<E>?)? = null) {

    suspend fun transition(event: Event): State<E> {
        val nextState = onEvent?.let { it(event) }
        var state: State<E>? = this
        nextState?.let {next ->
            onExit?.let { it() }
            state = next
            next.onEntry?.let { it() }
        }
//        if (nextState != null) {
//            onExit?.let { it() }
//            state = nextState
//            state.onEntry?.let { it() }
//        }
        return state ?: this
    }

    companion object {
        class StateBuilder<E> {
            private var name: String = ""
            private var onEntry: suspend () -> Unit = {}
            private var onExit: suspend () -> Unit = {}
            private var onEvent: suspend (Event) -> State<E>? = { null }

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
            fun onEvent(onEvent: suspend (Event) -> State<E>?) {
                this.onEvent = onEvent
            }

            fun build() = State(name, onEntry, onExit, onEvent)
        }

        fun <E> state(lambda: StateBuilder<E>.() -> Unit) =
                StateBuilder<E>()
                        .apply(lambda)
                        .build()

    }
}


class StateMachine<E>(var startingState: State<E>, var endState: State<E>, var channel: Channel<Event>) {
    suspend fun start() {
        var state = startingState
        while (state != endState) {
            state = state.transition(channel.receive())
            println("Current state: ${state.name}")
        }
    }
    companion object {
        class StateMachineBuilder<E> {
            private lateinit var startState: State<E>
            private lateinit var endState: State<E>
            private lateinit var channel: Channel<Event>

            fun startState(startState: () -> State<E>) {
                this.startState = startState()
            }
            fun endState(endState: () -> State<E>) {
                this.endState = endState()
            }
            fun channel(channel: () -> Channel<Event>) {
                this.channel = channel()
            }

            fun build() = StateMachine(startState, endState, channel)
        }

        fun <E>stateMachine(lambda: StateMachineBuilder<E>.() -> Unit) =
                StateMachineBuilder<E>()
                        .apply(lambda)
                        .build()
    }
}


sealed class Event {
    override fun toString() = this.javaClass.simpleName ?: "Event"
    object AuthFailed: Event()
    object Authenticated : Event()
    object AcceptedTerms : Event()
    object AcceptedConditions : Event()
    object Finish : Event()
    object Bogus: Event()
}


suspend fun main() {
    lateinit var state1: State<Event>
    lateinit var state2: State<Event>
    val terminal: State<Event> = state {
        name { "terminal"}
    }

    state1 = state {
        name { "State1" }
        onEntry {
            println("      state1>onEntry ")
        }
        onExit {
            println("      state1>onExit ")
        }
        onEvent {
            println("    state1>onEvent[$it] ")
            //using a when means
            //  1/ compiler will warn if events are missed
            //  2/ all events have to be handled in the satte, even if they are not relevant
            when (it) {
                is AuthFailed -> state1
                is Authenticated -> state2
                is AcceptedTerms -> state2
                is AcceptedConditions -> state2
                is Finish -> terminal
                else -> null
            }
        }
    }
    state2 = state {
        name { "State2" }
        onEntry {
            println("      state2>onEntry ")
            delay(200)
        }
        onExit {
            println("      state2>onExit ")
        }
        onEvent {
            println("    state2>onEvent[$it] ")
            when (it) {
                is AuthFailed -> state1
                is Finish -> terminal
                else -> null
            }
        }
    }

    val startingState = state1

    val channel = Channel<Event>(UNLIMITED)

    println("Start state: ${startingState.name}")

    val eventLoop = CoroutineScope(Dispatchers.Default).launch {
        stateMachine<Event> {
            startState { startingState }
            endState { terminal }
            channel { channel }
        }.start()
    }

    channel.offer(Authenticated)
    delay(1000)
    channel.offer(Authenticated)
    delay(1000)
    channel.offer(AcceptedTerms)
    delay(1000)
    channel.offer(AcceptedTerms)
    delay(1000)
    channel.offer(AuthFailed)
    delay(1000)
    channel.offer(Authenticated)
    delay(1000)
    channel.offer(Finish)
//    channel.offer(Bogus)
//    delay(1000)
//    channel.offer(Finish)

    eventLoop.join()
}
