package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型直连路由：按 modelsconfigbean 的 iid 解析出上游 OpenAI 兼容服务。
 * <p>
 * 一行配置 = 一个可直连的模型：protocol=openai 且 baseUrl / apiKey / upstreamModel 齐全。
 * 密钥只在服务端使用；浏览器只能拿到「是否已配置」的状态。
 */
@Slf4j
@Component
public class ModelGatewayResolver {

    public static final String MODULE_MODELS = "models";
    public static final String MODULE_NAME = "模型直连";
    public static final String PROTOCOL_OPENAI = "openai";

    @Autowired
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    /**
     * @return 解析结果；模型不存在、未配置直连或配置不全时返回 null
     */
    public ResolvedModel resolve(long iid) {
        ModelsConfigBean where = new ModelsConfigBean();
        where.setIid(iid);
        try {
            List<ModelsConfigBean> list = modelsConfigBeanHolder.queryList(where, null);
            if (list == null || list.isEmpty()) {
                return null;
            }
            return build(list.get(0));
        } catch (Exception e) {
            log.warn("[AiGateway] 读取直连模型配置失败, iid={}: {}", iid, e.getMessage());
            return null;
        }
    }

    /** 后台配置状态用：是否存在至少一个可直连的模型 */
    public boolean hasDirectModels() {
        ModelsConfigBean where = new ModelsConfigBean();
        where.setProtocol(PROTOCOL_OPENAI);
        try {
            List<ModelsConfigBean> list = modelsConfigBeanHolder.queryList(where, null);
            if (list == null) {
                return false;
            }
            for (ModelsConfigBean item : list) {
                if (build(item) != null) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 扫描直连模型失败: {}", e.getMessage());
        }
        return false;
    }

    private ResolvedModel build(ModelsConfigBean bean) {
        if (bean == null || !PROTOCOL_OPENAI.equalsIgnoreCase(bean.getProtocol())) {
            return null;
        }
        String baseUrl = trimUrl(bean.getBaseUrl());
        String apiKey = trimToNull(bean.getApiKey());
        String upstreamModel = trimToNull(bean.getUpstreamModel());
        if (baseUrl == null || apiKey == null || upstreamModel == null) {
            return null;
        }
        return new ResolvedModel(baseUrl, apiKey, upstreamModel, bean.getName());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimUrl(String url) {
        String result = trimToNull(url);
        if (result == null) {
            return null;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @Getter
    public static class ResolvedModel {
        private final String baseUrl;
        private final String apiKey;
        private final String upstreamModel;
        private final String name;

        public ResolvedModel(String baseUrl, String apiKey, String upstreamModel, String name) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.upstreamModel = upstreamModel;
            this.name = name;
        }
    }
}
