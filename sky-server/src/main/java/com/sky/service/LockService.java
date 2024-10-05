package com.sky.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

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
