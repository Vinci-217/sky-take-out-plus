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
