# sky-take-out-plus

声明：本项目基于[苍穹外卖源代码](https://github.com/shuhongfan/sky-take-out)二次开发

> 许多朋友们都非常热衷于苍穹外卖这个项目。这个项目是很好的作为初学者的项目，但是如果写到简历上，面试官会觉得很low，没有自己的思考。所以我要对这个项目进行优化，加入一些高级技术栈，让他成为俗而不凡的优质项目

## TODO

- [x] 引入Spring AI、实现AI对话的功能，基于原有Prompt对用户给出合理的点餐建议
- [x] 解决Redis缓存穿透、击穿、雪崩
- [x] 借助RabbitMQ实现异步订单处理，并使用WebSocket推送订单成功消息，优化用户下单体验
- [ ] 引入ElasticSearch实现菜品全局搜索

## 优化1：引入AI问答系统

AI对于我们来说越来越重要，所以引入AI对于项目来说也是一大两点

- SpringAI官网：[Spring AI ：： Spring AI 参考](https://docs.spring.io/spring-ai/reference/index.html)
- 一个推荐的学习资料：[一、Spring AI概述 (yuque.com)](https://www.yuque.com/pgthinker/spring-ai/pfky8r7geg65vqdd)

首先引入SpringAI的依赖

```
    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
        <repository>
            <id>spring-snapshots</id>
            <name>Spring Snapshots</name>
            <url>https://repo.spring.io/snapshot</url>
            <releases>
                <enabled>false</enabled>
            </releases>
        </repository>
    </repositories>
```

```
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
```

这里决定调用智普的API

文档参考：[智普 AI 聊天 ：： Spring AI 参考](https://docs.spring.io/spring-ai/reference/api/chat/zhipuai-chat.html)

相关依赖

```
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-zhipuai-spring-boot-starter</artifactId>
            <version>1.0.0-M2</version>
        </dependency>
```

### 实现对话功能

- 相关代码如下

```
package com.sky.controller.ai;

import org.springframework.ai.chat.client.ChatClient;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
class AIController {

    private final ChatClient chatClient;

    public AIController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/ai")
    String generation(String userInput) {
        return this.chatClient.prompt()
                .user(userInput)
                .call()
                .content();
    }
    
}
```

![image-20240923153902568](https://s2.loli.net/2024/09/23/HiSo21fOauVmeZl.png)

![image-20240923153927244](https://s2.loli.net/2024/09/23/G6i7cSI5lAqQYv2.png)

- 将代码修改后，实现流式显示对话内容

```
    @GetMapping(value = "/ai")
    Flux<String> generation(String userInput) {
        Flux<String> output = chatClient.prompt()
                .user(userInput)
                .stream()
                .content();
        return output;
    }
```

- 配置提示词

```
package com.sky.config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIConfiguration {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("你将作为一个营养家，给用户提出购餐建议。")
                .build();
    }
}

```

![image-20240923155432278](https://s2.loli.net/2024/09/23/cdUCrXf9HSI4nG7.png)

- 实现对话保存记忆（联系上下文）

```
    @GetMapping(value = "/ai", produces = "text/plain; charset=UTF-8")
    public String generation(String userInput) {

        // 发起聊天请求并处理响应
        String output = chatClient.prompt()
                .messages(historyMessage)
                .user(userInput)
                .call()
                .content();


        // 用户输入的文本是UserMessage
        historyMessage.add(new UserMessage(userInput));

        // 发给AI前对历史消息对列的长度进行检查
        if (historyMessage.size() > maxLen) {
            historyMessage = historyMessage.subList(historyMessage.size() - maxLen - 1, historyMessage.size());
        }

        return output;
    }
```

完成AI对话问答的功能

## 优化2：解决Redis缓存的穿透、击穿、雪崩问题

由于我们在项目中使用了Redis作为缓存，所以不可避免的可能会遇到缓存的三大问题，尤其是遇到点餐高峰期的时候。

对应代码如下：

```
package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Api(tags = "C端-菜品浏览接口")
public class DishController {
    @Autowired
    private DishService dishService;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 根据分类id查询菜品
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
//        构造redis中的key，规则：dish_分类Id
        String key = "dish_" + categoryId;

//        查询redis中是否存在菜品数据
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null && list.size() > 0) {
//            如果存在，直接返回，无需查询数据库
            return Result.success(list);
        }


//        如果不存在，查询数据库，将查询到的数据放入redis中
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);//查询起售中的菜品

        list = dishService.listWithFlavor(dish);

//        放入redis
        redisTemplate.opsForValue().set(key, list);
        return Result.success(list);
    }


}
```

我们针对这段代码对于缓存的三大问题进行解决。

我们先来回顾一下缓存的这三个问题：

- 缓存穿透：如果查询一个不存在的值，那么Reids里面肯定是没有这个值的，所以请求就会到达数据库。如果这个请求量很大的话，数据库就容易被打崩。一般来说这种情况多发生于黑客攻击数据库的时候，他们恶意伪造一些查询请求，大量打到数据库，恶意破坏数据库。
- 缓存击穿：有些我们放到Redis里面，是因为他的查询次数很多，我们为了缓解数据库的压力所以放到缓存里。而且我们一般Redis的数据是有过期时间的。那么一旦热点访问的这个数据在Redis中过期了，那么就会发送大量请求到数据库，这样数据库就容易被打崩。
- 缓存雪崩：Redis突然宕机了，原本要查询Redis的数据被打到数据库了。或者Redis中的很多数据突然都过期了，那么这时候大量数据就会打到数据库。造成数据库的压力过大，容易崩溃。

解决这三个问题我们分别需要不同的方法：

### 缓存穿透

一般常用的方法有缓存Null值，使用布隆过滤器等，此外还有通过限流算法等方法，这里我们通过布隆过滤器的方法进行解决。

需要注意的是，如果数据有了更新，我们需要将更新的数据也刷到布隆过滤器中。

首先引入依赖：

```
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>31.0.1-jre</version>
</dependency>
```

然后自定义一个布隆过滤器的Service（注意：category部分查询所有id的代码需要自行实现，这里不再赘述）

```
package com.sky.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.util.List;

@Service
public class BloomFilterService {
    private BloomFilter<Long> bloomFilter;
    
    @Autowired
    private CategoryService categoryService;

    @PostConstruct
    public void init() {
        int expectedInsertions = 1000; // 预期插入的分类ID数量
        double fpp = 0.01; // 误判率
        bloomFilter = BloomFilter.create(Funnels.longFunnel(), expectedInsertions, fpp);

        // 加载有效分类ID到布隆过滤器
        loadValidCategoryIds();
    }

    private void loadValidCategoryIds() {
        // 从数据库查询所有有效的分类ID
        List<Long> validCategoryIds = fetchValidCategoryIdsFromDatabase();
        // 将有效分类ID添加到布隆过滤器
        validCategoryIds.forEach(bloomFilter::put); 
    }

    private List<Long> fetchValidCategoryIdsFromDatabase() {
        return categoryService.listAllIds(); // 示例数据
    }

    public boolean mightContain(Long categoryId) {
        return bloomFilter.mightContain(categoryId);
    }
}
```

之后改造DishController的list方法

```
    public Result<List<DishVO>> list(Long categoryId) {

        // 使用布隆过滤器检查分类ID是否可能存在
        if (!bloomFilterService.mightContain(categoryId)) {
            // 如果布隆过滤器返回不存在，直接返回空结果
            return Result.success(Collections.emptyList()); // 或返回特定的错误信息
        }

//        构造redis中的key，规则：dish_分类Id
        String key = "dish_" + categoryId;

//        查询redis中是否存在菜品数据
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null && list.size() > 0) {
//            如果存在，直接返回，无需查询数据库
            return Result.success(list);
        }


//        如果不存在，查询数据库，将查询到的数据放入redis中
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);//查询起售中的菜品

        list = dishService.listWithFlavor(dish);

//        放入redis
        redisTemplate.opsForValue().set(key, list);
        return Result.success(list);
    }
```

### 缓存击穿

缓存击穿的解决方法一般也有两种：互斥锁和逻辑过期。互斥锁无疑会降低性能，但逻辑过期也可能导致数据不一致等问题。

- 互斥锁的方法：

如果我们后端采用微服务架构部署的话，那么是运行在不同的机器上，有不同的JVM虚拟机。如果我们使用Java内置的锁的话，请求到两台机器上，就会造成获取到两个锁。而这就违背了我们使用锁的初衷，所以我们这里通过Redis的SETNX方法来实现互斥锁。

我们先自定义一个互斥锁的Service

```
@Service
public class LockService {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public boolean tryLock(String lockKey, String lockValue, long timeout) {
        // 尝试获取锁
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue))) {
            // 设置锁的过期时间
            redisTemplate.expire(lockKey, timeout, TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    public void releaseLock(String lockKey, String lockValue) {
        // 只有当前持有锁的线程才能释放锁
        if (lockValue.equals(redisTemplate.opsForValue().get(lockKey))) {
            redisTemplate.delete(lockKey);
        }
    }
}
```

然后再list方法中使用互斥锁

```
    public Result<List<DishVO>> list(Long categoryId) {

        // 使用布隆过滤器检查分类ID是否可能存在
        if (!bloomFilterService.mightContain(categoryId)) {
            // 如果布隆过滤器返回不存在，直接返回空结果
            return Result.success(Collections.emptyList()); // 或返回特定的错误信息
        }

//        构造redis中的key，规则：dish_分类Id
        String key = "dish_" + categoryId;

//        查询redis中是否存在菜品数据
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null && list.size() > 0) {
//            如果存在，直接返回，无需查询数据库
            return Result.success(list);
        }


        // 如果redis中没有数据，则进行数据库查询
        String lockKey = "lock:dish:" + categoryId;
        String lockValue = String.valueOf(System.currentTimeMillis() + 30000); // 锁的过期时间（30秒）

        boolean lockAcquired = false;

        try {
            // 尝试获取锁
            lockAcquired = lockService.tryLock(lockKey, lockValue, 30);
            if (lockAcquired) {
                // 再次查询Redis，防止其他线程已经填充了缓存
                list = (List<DishVO>) redisTemplate.opsForValue().get(key);
                if (list != null && !list.isEmpty()) {
                    return Result.success(list);
                }

                // 从数据库查询
                Dish dish = new Dish();
                dish.setCategoryId(categoryId);
                dish.setStatus(StatusConstant.ENABLE); // 查询起售中的菜品
                list = dishService.listWithFlavor(dish);

                // 放入redis
                redisTemplate.opsForValue().set(key, list, 10, TimeUnit.MINUTES); // 设置缓存过期时间
                return Result.success(list);
            } else {
                return Result.error("服务器忙，请稍后再试");
            }
        } finally {
            // 仅在成功获取锁的情况下释放锁
            if (lockAcquired) {
                lockService.releaseLock(lockKey, lockValue);
            }
        }

    }
```

整个互斥锁的流程是：当我的一个线程在缓存中找不到数据，那么就获得锁，然后去数据库查。其他线程此时由于被锁住了，所以是查询失败的状态。等我的用户查询完毕释放锁以后，其他线程再次查询才可以获得结果。

- 逻辑过期的方法：

逻辑过期的方法，就是我的Redis的key并不是实际上真的过期了，而是我在Redis中存入我认为有效的时间，查询的时候看他过期了没。如果逻辑上判断没有过期，那么就返回缓存数据；如果逻辑上判断过期了，就返回旧的脏数据，并且开一个独立线程去更新数据。

这里暂时不实现逻辑过期的方法。

### 缓存雪崩

缓存雪崩的解决方法一般有多级缓存、设置随机过期时间

- 设置随机TTL：

我们只需要在原有代码的设置Redis固定过期时间部分进行修改即可

```
import java.util.concurrent.ThreadLocalRandom;

// 设置随机过期时间，范围在5到15分钟之间
long randomTimeout = ThreadLocalRandom.current().nextLong(5, 16) * 60; // 转换为秒
// 放入redis
redisTemplate.opsForValue().set(key, list, randomTimeout, TimeUnit.MINUTES); // 设置缓存过期时间
```

- 使用多级缓存：

我们采用Caffine作为本地缓存，Redis作为分布式缓存，两级缓存来解决缓存雪崩的问题

引入Caffine的依赖：

```
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.0.5</version>
</dependency>
```

进行配置

```
package com.sky.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sky.vo.DishVO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfiguration {
    @Bean
    public Cache<Long, List<DishVO>> localCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES) // 设置过期时间
                .maximumSize(1000) // 设置最大缓存项数量
                .build();
    }
}
```

改造DishController方法

```
package com.sky.controller.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.BloomFilterService;
import com.sky.service.DishService;
import com.sky.service.LockService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Api(tags = "C端-菜品浏览接口")
public class DishController {
    @Autowired
    private DishService dishService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private BloomFilterService bloomFilterService;

    @Autowired
    private LockService lockService;

    @Autowired
    private Cache<Long, List<DishVO>> localCache; // 注入Caffeine缓存

    /**
     * 根据分类id查询菜品
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    @Transactional // 开启事务，保证多级缓存和数据库的一致性
    public Result<List<DishVO>> list(Long categoryId) {

        // 使用布隆过滤器检查分类ID是否可能存在
        if (!bloomFilterService.mightContain(categoryId)) {
            // 如果布隆过滤器返回不存在，直接返回空结果
            return Result.success(Collections.emptyList()); // 或返回特定的错误信息
        }

//        构造redis中的key，规则：dish_分类Id
        String key = "dish_" + categoryId;

        // 查询本地缓存
        List<DishVO> localList = localCache.getIfPresent(categoryId);
        if (localList != null) {
            return Result.success(localList);
        }

//        查询redis中是否存在菜品数据
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null && list.size() > 0) {
//            如果存在，直接返回，无需查询数据库
            return Result.success(list);
        }


        // 如果redis中没有数据，则进行数据库查询
        String lockKey = "lock:dish:" + categoryId;
        String lockValue = String.valueOf(System.currentTimeMillis() + 30000); // 锁的过期时间（30秒）

        boolean lockAcquired = false;

        try {
            // 尝试获取锁
            lockAcquired = lockService.tryLock(lockKey, lockValue, 30);
            if (lockAcquired) {
                // 再次查询Redis，防止其他线程已经填充了缓存
                list = (List<DishVO>) redisTemplate.opsForValue().get(key);
                if (list != null && !list.isEmpty()) {
                    localCache.put(categoryId, list);
                    return Result.success(list);
                }

                // 从数据库查询
                Dish dish = new Dish();
                dish.setCategoryId(categoryId);
                dish.setStatus(StatusConstant.ENABLE); // 查询起售中的菜品
                list = dishService.listWithFlavor(dish);

                // 设置随机过期时间，范围在5到15分钟之间
                long randomTimeout = ThreadLocalRandom.current().nextLong(5, 16) * 60; // 转换为秒
                // 放入redis
                redisTemplate.opsForValue().set(key, list, randomTimeout, TimeUnit.MINUTES); // 设置缓存过期时间
                // 将数据放入本地缓存
                localCache.put(categoryId, list);
                return Result.success(list);
            } else {
                return Result.error("服务器忙，请稍后再试");
            }
        } finally {
            // 仅在成功获取锁的情况下释放锁
            if (lockAcquired) {
                lockService.releaseLock(lockKey, lockValue);
            }
        }
    }

}
```

至此，缓存的三大问题解决完毕！

补充：作者后来才知道Redisson原来有现成的方法，后续抽空使用Redisson优化

## 优化3：借助RabbitMQ异步订单处理，WebSocket推送消息到客户端

在中午和晚上的高峰期时刻，有时候可能会出现用户下订单的时候等待时间过长，不知道订单是否下单成功的情况，于是我们决定采用RabbitMQ对此进行优化。即将订单先放到消息队列中，然后等消息队列里面的订单处理完成之后，再通过WebSocket返回到客户端

### RabbitMQ

RabbitMQ是一个消息队列中间件，首先有生产者和消费者两部分，生产者负责发送信息到MQ、消费者负责从MQ中获取信息。

当消息从生产者发送到MQ时，MQ首先通过Exchange将消息路由转发到某个队列，然后在这个队列里面存储这个消息。

RabbitMQ有四种交换机模式：direct、fanout、topic、headers。默认的模式是direct，即发送的时候携带一个路由键从而转发到某个队列。我们这里使用做简单的模式即可。

### WebSocket

WebSocket是一个双向持久性通信协议。当客户端发送请求到后端时，请求头中包含某些WebSocket的字段，表示想要升级为WebSocket，然后服务器端接受以后进行升级操作，数据以帧的单位进行发送。

苍穹外卖项目中已经预先配置好了WebSocket，这里我们只需要在WebSocketServer类中添加几个方法即可，方便后续调用

```
    /**
     *  根据userId发送给用户
     * @param uid
     * @param msg
     */
    public void sendMessageById(String uid, String msg) {
        sessionMap.forEach((sessionId, session) -> {
            //发给指定的接收用户
            if (sessionId.equals(uid)){
                sendMessageBySession(session, msg);
            }
        });
    }

    /**
     *  根据Session发送消息给用户
     * @param session
     * @param message
     */
    public void sendMessageBySession(Session session, String message) {
        try {
            session.getBasicRemote().sendText(message);
        } catch (IOException e) {
            log.error("----[ WebSocket ]------给用户发送消息失败---------");
            e.printStackTrace();
        }
    }
```

Docker安装RabbitMQ

```
docker pull rabbitmq:latest
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:latest
```

导入RabbitMQ的依赖

```
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
```

yml文件进行配置

```
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

对RabbitMQ配置

```
package com.sky.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Binding;

@Configuration
public class RabbitMQConfig {
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

```

针对用户的下订单的Controller，修改submit方法（需要先注入RabbitTemplate

```
    @PostMapping("/submit")
    @ApiOperation("用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO) {
        log.info("用户下单：{}", ordersSubmitDTO);
        // 将下单请求发送到 RabbitMQ 队列
        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_ROUTING_KEY, ordersSubmitDTO);
        return Result.success("下单请求已接收，正在处理中...");
    }
```

然后新建一个表示Rabbit的Listener的类

```
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
```

这段代码中，通过@RabbitListener注解接受对应队列的消息，然后处理接收到的订单请求，并将处理后的结果通过WebSocket发送给客户端

至此，我们就完成了异步订单处理的功能
