package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.DifyConfigBean;
import com.aiproject.ai.service.mapper.DifyConfigBeanMapper;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DifyConfigBeanHolder {

    @Autowired
    DifyConfigBeanMapper<DifyConfigBean> difyConfigBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    /**
     * Dify 配置里含 apiUrl / apiKey，属平台级敏感配置，
     * 前端只在 Admin/DifyConfigView 维护。后端此前 update 无任何校验，
     * 任意登录用户都能把 apiUrl 改到自己控制的地址（相当于改写全站 AI 流量出口），
     * 或替换 apiKey。这里收紧为仅超管可写。
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护 Dify 配置");
        }
        return null;
    }

    public Result<Boolean> install(String uid, DifyConfigBean data) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        return installInternal(data);
    }

    /**
     * 内部插入：不做管理员校验。
     * <p>
     * 仅供启动期数据注册器（DifyConfigDataRegister）等内部代码调用，
     * 不得暴露到任何 HTTP 接口上。
     */
    public Result<Boolean> installInternal(DifyConfigBean data) {
        if (difyConfigBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    public DifyConfigBean query(DifyConfigBean data) {
        List<DifyConfigBean> list = difyConfigBeanMapper.select(null, data);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    public Result<Boolean> update(String uid, DifyConfigBean data, DifyConfigBean where) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        /*
         * 前端编辑时密钥框留空表示「不修改」：queryList 已脱敏不下发密钥，
         * 若直接以空值覆盖会把已配置的 apiKey 清掉，这里按 where.iid 回读补齐。
         */
        if ((data.getApiKey() == null || data.getApiKey().trim().isEmpty())
                && where != null && where.getIid() != null) {
            DifyConfigBean existing = queryByIid(where.getIid());
            if (existing != null && existing.getApiKey() != null) {
                data.setApiKey(existing.getApiKey());
            }
        }
        data.setLastuser(uid);
        if (difyConfigBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    private DifyConfigBean queryByIid(Long iid) {
        DifyConfigBean where = new DifyConfigBean();
        where.setIid(iid);
        List<DifyConfigBean> list = difyConfigBeanMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询 Dify 配置（含 apiUrl / apiKey）。
     * <p>
     * 仅供后台「Dify 配置管理」使用；浏览器不再直接拿 Key 调 Dify，
     * 所有 AI 请求统一走 /api/aiGateway/**，由 AiGatewayConfigResolver 在服务端读取配置。
     * 因此这里收紧为仅超管可查，避免任意登录用户通过该接口取走平台密钥。
     */
    public Result<List<DifyConfigBean>> queryList(String uid, DifyConfigBean data) {
        if (uid == null) {
            return Result.failed(null, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(null, "仅管理员可查看 Dify 配置");
        }
        PageRequest pageRequest = new PageRequest();
        pageRequest.setOrder("iid");
        pageRequest.setDesc("asc");
        List<DifyConfigBean> list = difyConfigBeanMapper.select(pageRequest, data);
        // 脱敏：apiKey 属平台级密钥，HTTP 响应一律置空，前端以「是否已配置」展示。
        // 注意：网关运行时通过内部 query() 读取真实密钥，此处不影响 AI 调用。
        if (list != null) {
            for (DifyConfigBean item : list) {
                item.setApiKeyConfigured(item.getApiKey() != null
                        && !item.getApiKey().trim().isEmpty());
                item.setApiKey("");
            }
        }
        return Result.succeed(list, "查询成功");
    }
}
