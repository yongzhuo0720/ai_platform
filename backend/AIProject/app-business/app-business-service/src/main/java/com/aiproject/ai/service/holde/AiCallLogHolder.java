package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * AI 调用审计日志写入。
 * <p>
 * 写日志失败不能影响 AI 请求本身，因此这里吞掉异常并记录 warn。
 */
@Slf4j
@Service
public class AiCallLogHolder {

    @Autowired
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    public void record(AiCallLogBean entry) {
        if (entry == null) {
            return;
        }
        try {
            entry.setCreatetime(new Timestamp(System.currentTimeMillis()));
            if (entry.getEnable() == null) {
                entry.setEnable(true);
            }
            if (entry.getLastuser() == null) {
                entry.setLastuser(entry.getUserId());
            }
            callLogBeanMapper.install(entry);
        } catch (Exception e) {
            log.warn("[AiGateway] 写审计日志失败, uid={}, endpoint={}: {}",
                    entry.getUserId(), entry.getEndpoint(), e.getMessage());
        }
    }
}
