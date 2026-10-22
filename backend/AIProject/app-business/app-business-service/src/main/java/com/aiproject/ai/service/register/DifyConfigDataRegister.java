package com.aiproject.ai.service.register;

import com.aiproject.ai.service.bean.DifyConfigBean;
import com.aiproject.ai.service.holde.DifyConfigBeanHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DifyConfigDataRegister implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    DifyConfigBeanHolder difyConfigBeanHolder;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initDifyConfig();
    }

    private void initDifyConfig() {
        // 检查表中是否已有数据，避免重复初始化
        DifyConfigBean probe = new DifyConfigBean();
        DifyConfigBean existing = difyConfigBeanHolder.query(probe);
        if (existing != null) {
            log.info("Dify配置数据已存在，跳过初始化");
            return;
        }

        // 初始化3条模块配置，URL和Key留空
        String[][] defaults = {
            {"问答聊天对话", "用于聊天对话模块的Dify API配置"},
            {"知识库对话/工作流", "用于知识库对话和工作流模块的Dify API配置"},
            {"知识库文件", "用于知识库文件管理的Dify API配置"},
        };

        for (String[] item : defaults) {
            DifyConfigBean bean = new DifyConfigBean();
            bean.setModuleName(item[0]);
            bean.setDescription(item[1]);
            difyConfigBeanHolder.installInternal(bean);
        }
        log.info("Dify配置初始化完成，已插入{}条记录", defaults.length);
    }
}
