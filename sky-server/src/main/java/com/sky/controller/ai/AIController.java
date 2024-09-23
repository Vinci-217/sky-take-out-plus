package com.sky.controller.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@RestController
@Slf4j
class AIController {

    private final ChatClient chatClient;
    // 历史消息列表
    static List<Message> historyMessage = new ArrayList<>();
    // 历史消息列表的最大长度
    static int maxLen = 10;

    AIController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // TODO: 流式响应和UTF-8编码的冲突尚未解决
    @GetMapping(value = "/ai", produces = "text/plain; charset=UTF-8")
    Flux<String> generation(String userInput) {
        // 用户输入的文本是UserMessage
        historyMessage.add(new UserMessage(userInput));

        // 发给AI前对历史消息对列的长度进行检查
        if(historyMessage.size() > maxLen){
            historyMessage = historyMessage.subList(historyMessage.size()-maxLen-1,historyMessage.size());
        }

        // TODO: 实现历史消息

        // 发起聊天请求并处理响应
        Flux<String> output = chatClient.prompt()
                .user(userInput)
                .stream()
                .content();
        return output;
    }

}