# sky-take-out-plus

声明：本项目基于[苍穹外卖源代码](https://github.com/shuhongfan/sky-take-out)二次开发

> 许多朋友们都非常热衷于苍穹外卖这个项目。这个项目是很好的作为初学者的项目，但是如果写到简历上，面试官会觉得很low，没有自己的思考。所以我要对这个项目进行优化，加入一些高级技术栈，让他成为俗而不凡的优质项目

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

