package com.plexiti.integration

import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.CommandCallback
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.GenericCommandMessage
import org.axonframework.eventhandling.EventBus
import org.axonframework.eventhandling.GenericEventMessage
import org.axonframework.eventhandling.saga.SagaEventHandler
import org.axonframework.eventhandling.saga.SagaLifecycle
import org.axonframework.queryhandling.GenericQueryMessage
import org.axonframework.queryhandling.QueryBus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.jvmErasure

// TODO support explicit message names
// TODO alternatives: implement an Axon Saga Manager
// TODO inheritance: replace abstract class with something better
// One could e.g. hook in the saga event handlers into every saga
// annotated with @Flow

/**
 * @author Martin Schimak <martin.schimak@plexiti.com>
 */
abstract class AxonFlowIntegration {

    @Autowired @Transient
    private lateinit var commandBus: CommandBus

    @Autowired @Transient
    private lateinit var eventBus: EventBus

    @Autowired @Transient
    private lateinit var queryBus: QueryBus

    @Autowired @Transient
    private lateinit var messageFactory: MessageFactory

    @Autowired @Transient
    private lateinit var responseHandler: ResponseHandler

    // Main association id with which saga is started
    private lateinit var sagaAssociationId: String

    // Mapping of completion event names to flowAssociationIds
    private var successEvents = mutableMapOf<String, String>()
    private var failureEvents = mutableMapOf<String, String>()

    @SagaEventHandler(associationProperty = "sagaAssociationId")
    internal fun on(event: FlowCommandIssued) {

        // Flow awaits completion of command
        if (event.flowAssociationId != null) {

            commandBus.dispatch(GenericCommandMessage(messageFactory.create(event.command, this)), object : CommandCallback<Any, Any> {

                override fun onSuccess(commandMessage: CommandMessage<out Any>?, result: Any?) {

                    // Flow awaits "completion" via defined events
                    if (event.success != null) {

                        successEvents = successEvents.apply { put(event.success, event.flowAssociationId) }
                        if (event.failure != null) {
                            failureEvents = failureEvents.apply { put(event.failure, event.flowAssociationId) }
                        }

                    // Flow awaits completion via success or failure notification
                    } else {

                        eventBus.publish(GenericEventMessage(FlowCommandSucceeded(event.flowAssociationId)))

                    }

                }

                override fun onFailure(commandMessage: CommandMessage<out Any>?, cause: Throwable) {

                    // Send back propagated exceptions as failure for both completion scenarios
                    eventBus.publish(GenericEventMessage(FlowCommandFailed(event.flowAssociationId, cause::class.java.canonicalName, cause.message)))

                }

            })

        // Flow fires command and moves on
        } else {

            commandBus.dispatch(GenericCommandMessage(messageFactory.create(event.command, this)))

        }

    }

    @SagaEventHandler(associationProperty = "sagaAssociationId")
    internal fun on(event: FlowEventRaised) {

        eventBus.publish(GenericEventMessage(messageFactory.create(event.event, this)))

    }

    @SagaEventHandler(associationProperty = "sagaAssociationId")
    internal fun on(event: FlowQueryRequested) {

        val query = messageFactory.create(event.query, this)
        val queryResponseClass = responseHandler.responseClass(query::class, this)

        // TODO resilience: asynchrony?

        responseHandler.handle(queryBus.query(GenericQueryMessage(query, queryResponseClass.java)).get(), this)
        eventBus.publish(GenericEventMessage(FlowQueryResponded(event.flowAssociationId, bindValuesToFlow())))

    }

    protected fun correlateEventToFlow(event: Any, sagaAssociationId: String? = null) {

        if (sagaAssociationId != null) {
            this.sagaAssociationId = sagaAssociationId
            SagaLifecycle.associateWith("sagaAssociationId", sagaAssociationId)
        }

        val eventName = event::class.qualifiedName!!

        // Flow informed of successful command completion via event
        val eventMessage: Any = if (successEvents.contains(eventName)) {

            val flowAssociationId = successEvents.remove(eventName)!!
            failureEvents.filter { it.value.equals(flowAssociationId) }.forEach {
                failureEvents.remove(it.key)
            }
            FlowCommandSucceeded(flowAssociationId, bindValuesToFlow())

        // Flow informed of failed command completion via event
        } else if (failureEvents.contains(eventName)) {

            val flowAssociationId = failureEvents.remove(eventName)!!
            successEvents.filter { it.value.equals(flowAssociationId) }.forEach {
                successEvents.remove(it.key)
            }
            FlowCommandFailed(flowAssociationId, eventName)

        // Flow informed of external event trigger
        } else {

            FlowEventReceived(this.sagaAssociationId, eventName, bindValuesToFlow())

        }

        eventBus.publish(GenericEventMessage(eventMessage))

    }

    protected open fun bindValuesToFlow() = emptyMap<String, Any>()

}

// TODO refactor: signatures / parameter ordering

data class FlowCommandIssued(val sagaAssociationId: String, val command: String, val flowAssociationId: String? = null, val success: String? = null, val failure: String? = null)
data class FlowCommandSucceeded(val flowAssociationId: String, val variables: Map<String, Any?>? = null)
data class FlowCommandFailed(val flowAssociationId: String, val errorCode: String, val errorMessage: String? = null)
data class FlowEventRaised(val sagaAssociationId: String, val flowAssociationId: String, val event: String)
data class FlowEventReceived(val sagaAssociationId: String, val event: String, val variables: Map<String, Any?>? = null)
data class FlowQueryRequested(val sagaAssociationId: String, val flowAssociationId: String, val query: String)
data class FlowQueryResponded(val flowAssociationId: String, val variables: Map<String, Any?>)

@Component
internal class MessageFactory {

    private val factories: MutableMap<KClass<*>, SagaMessageFactory> = mutableMapOf()

    fun create(messageName: String, saga: Any): Any {
        return factory(saga).create(messageName, saga)
    }

    private fun factory(saga: Any): SagaMessageFactory {
        var factory = factories[saga::class]
        if (factory == null) {
            factory = SagaMessageFactory(saga::class)
            factories[saga::class] = SagaMessageFactory(saga::class)
        }
        return factory
    }

}

@Component
internal class ResponseHandler {

    private val handlers: MutableMap<KClass<*>, SagaResponseHandler> = mutableMapOf()

    fun responseClass(queryClass: Any, saga: Any): KClass<*> {
        return handler(saga).responseClass(queryClass)
    }

    fun handle(queryResponse: Any, saga: Any) {
        handler(saga).handle(queryResponse, saga)
    }

    private fun handler(saga: Any): SagaResponseHandler {
        var handler = handlers[saga::class]
        if (handler == null) {
            handler = SagaResponseHandler(saga::class)
            handlers[saga::class] = SagaResponseHandler(saga::class)
        }
        return handler
    }

}

internal class SagaMessageFactory(sagaClass: KClass<*>): Serializable {

    private val factories: Map<KClass<*>, KFunction<*>>
            = sagaClass.functions.filter {
        it.findAnnotation<FlowCommandFactory>() != null
            || it.findAnnotation<FlowEventFactory>() != null
            || it.findAnnotation<FlowQueryFactory>() != null
    }.associateBy {
        it.returnType.jvmErasure
    }

    fun create(messageName: String, saga: Any): Any {
        val messageClass = Class.forName(messageName).kotlin
        val factoryMethod = factories[messageClass]
                ?: throw IllegalStateException("No factory method defined for message class ${messageName}!")
        return factoryMethod.call(saga)!!
    }

}

internal class SagaResponseHandler(sagaClass: KClass<*>) {

    private val responseHandlers: Map<KClass<*>, KFunction<*>>
            = sagaClass.functions.filter {
        it.findAnnotation<FlowResponseHandler>() != null
    }.associateBy {
        if (it.parameters.size == 2) it.parameters[1].type.jvmErasure
        else throw IllegalStateException("Flow Response Handler ${it.name} must have a single parameter!")
    }

    private val responseTypes: Map<KClass<*>, KClass<*>>
            = sagaClass.functions.filter {
        it.findAnnotation<FlowQueryFactory>() != null
    }.associate {
        it.returnType.jvmErasure to it.findAnnotation<FlowQueryFactory>()!!.responseType
    }

    fun responseClass(queryClass: Any): KClass<*> {
        return responseTypes[queryClass]!!
    }

    fun handle(queryResponse: Any, saga: Any) {
        val handlerMethod = responseHandlers[queryResponse::class]
            ?: throw IllegalStateException("No handler method defined for query response class ${queryResponse::class}!")
        handlerMethod.call(saga, queryResponse)
    }

}
