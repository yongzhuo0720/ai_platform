package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.search.GlobalSearchHolder;
import com.aiproject.ai.service.search.dto.GlobalSearchRequest;
import com.aiproject.ai.service.search.dto.GlobalSearchResponse;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局搜索（命令面板）入口。
 *
 * <p>只有一个只读聚合接口。所有归属与可见性判断都在
 * {@link GlobalSearchHolder} 调用的领域 Holder 内完成。</p>
 */
@RestController
@Slf4j
@RequestMapping("/api/globalSearch")
@Api(value = "全局搜索", tags = "全局搜索")
public class GlobalSearchController {

    @Autowired
    GlobalSearchHolder globalSearchHolder;

    @ApiOperation(value = "聚合搜索", httpMethod = "POST",
            notes = "按当前登录用户权限返回 Agent / 知识库 / RAG 评估 / 模型 / 数据集")
    @PostMapping("/search")
    public Result<GlobalSearchResponse> search(@LoginUserId String uid,
                                               @RequestBody(required = false) GlobalSearchRequest data) {
        return globalSearchHolder.search(uid, data);
    }
}
