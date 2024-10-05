package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.sky.config.RabbitMQConfig;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.vo.OrderSubmitVO;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OrderMessageListener {

    @Autowired
    private OrderService orderService;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 接收订单消息
     * @param ordersSubmitDTO
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    public void receiveOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 处理下单逻辑
        OrderSubmitVO orderSubmitVO = orderService.submitOrder(ordersSubmitDTO);
        log.info("处理下单请求：{}", ordersSubmitDTO);
        Long uid = Thread.currentThread().getId();
        // 发送websocket消息给客户端
        webSocketServer.sendMessageById(String.valueOf(uid), JSON.toJSONString(orderSubmitVO));
    }
}
