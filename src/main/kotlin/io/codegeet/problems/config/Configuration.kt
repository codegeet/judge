package io.codegeet.problems.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Clock


@Configuration
class Configuration() {

    @Bean
    fun objectMapperBuilder(): Jackson2ObjectMapperBuilder = Jackson2ObjectMapperBuilder.json()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .modulesToInstall(KotlinModule.Builder().build())

    @Bean
    fun producerJackson2MessageConverter(): Jackson2JsonMessageConverter {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        return Jackson2JsonMessageConverter(objectMapper)
    }

    @Bean
    fun objectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper = builder.build()

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    companion object {
        val QUEUE_NAME: String = "rpc_queue"
    }

    @Bean
    fun queue(): Queue {
        return Queue(QUEUE_NAME, false)
    }

    @Bean
    fun rabbitTemplate(connectionFactory: ConnectionFactory,
                       jsonMessageConverter: Jackson2JsonMessageConverter): RabbitTemplate {
        val rabbitTemplate = RabbitTemplate(connectionFactory)
        rabbitTemplate.messageConverter = jsonMessageConverter
        rabbitTemplate.setReplyTimeout(6000)
        return rabbitTemplate
    }
}
