package com.sky.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Binding;

@Configuration
public class RabbitMQConfig {
    // 队列、交换机、路由键
    public static final String ORDER_QUEUE = "order.queue";
    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String ORDER_ROUTING_KEY = "order.routing.key";

    /**
     * 创建队列
     * @return
     */
    @Bean
    public Queue orderQueue() {
        return new Queue(ORDER_QUEUE);
    }

    /**
     * 创建交换机
     * @return
     */
    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    /**
     * 绑定队列到交换机
     * @param orderQueue
     * @param orderExchange
     * @return
     */
    @Bean
    public Binding orderBinding(Queue orderQueue, TopicExchange orderExchange) {
        return org.springframework.amqp.core.BindingBuilder
                .bind(orderQueue)
                .to(orderExchange)
                .with(ORDER_ROUTING_KEY);
    }
}
