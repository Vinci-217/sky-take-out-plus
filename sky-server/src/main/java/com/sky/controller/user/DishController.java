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
