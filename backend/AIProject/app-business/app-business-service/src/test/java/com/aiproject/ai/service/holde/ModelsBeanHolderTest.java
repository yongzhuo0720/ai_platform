package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.ModelsBean;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.bean.ModelsDownloadsBean;
import com.aiproject.ai.service.mapper.ModelsBeanMapper;
import com.aiproject.ai.service.mapper.ModelsDownloadsBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型仓库下载统计的测试。
 *
 * <p>针对两个曾经真实存在的统计缺陷：</p>
 * <ol>
 *   <li>{@code addDownloads} 不校验 iid、不去重——{@code getDownloadUrl}
 *       每次拉文件列表都会调一次，用户反复刷新页面下载量就持续上涨；</li>
 *   <li>{@code queryWeeklyDownloads} 收了 {@code ModelsBean data} 却完全没用，
 *       SQL 只按本周时间过滤，返回的是全站周下载总量，每个模型显示同一个数。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ModelsBeanHolderTest {

    @Mock
    ModelsBeanMapper<ModelsBean> modelsBeanMapper;

    @Mock
    ModelsDownloadsBeanMapper<ModelsDownloadsBean> modelsDownloadsBeanMapper;

    @Mock
    ModelsDownloadsBeanHolder modelsDownloadsBeanHolder;

    @Mock
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    ModelsBeanHolder holder;

    private ModelsBean model(Long iid) {
        ModelsBean m = new ModelsBean();
        m.setIid(iid);
        return m;
    }

    /** 同一用户对同一模型只计一次下载，重复调用不再落库 */
    @Test
    void addDownloads_shouldNotDoubleCount() {
        ModelsBean data = model(7L);
        // 已有一条 (uid=u1, moudelsiid=7) 的记录
        ModelsDownloadsBean existing = new ModelsDownloadsBean();
        existing.setMoudelsiid(7L);
        existing.setUser("u1");
        when(modelsDownloadsBeanHolder.queryList(any(ModelsDownloadsBean.class), isNull()))
                .thenReturn(Collections.<ModelsDownloadsBean>singletonList(existing));

        Result<Boolean> result = holder.addDownloads("u1", data);

        assertTrue(Boolean.TRUE.equals(result.getDatas()), "已记录过不应报错");
        verify(modelsDownloadsBeanHolder, never()).install(any(ModelsDownloadsBean.class));
    }

    /** 第一次下载：正常落库 */
    @Test
    void addDownloads_shouldRecordFirstDownload() {
        when(modelsDownloadsBeanHolder.queryList(any(ModelsDownloadsBean.class), isNull()))
                .thenReturn(Collections.<ModelsDownloadsBean>emptyList());
        when(modelsDownloadsBeanHolder.install(any(ModelsDownloadsBean.class)))
                .thenReturn(Result.succeed(true, "增加成功"));

        Result<Boolean> result = holder.addDownloads("u1", model(7L));

        assertTrue(Boolean.TRUE.equals(result.getDatas()));
        verify(modelsDownloadsBeanHolder).install(any(ModelsDownloadsBean.class));
    }

    /** iid 为空必须拒绝，否则会落一条搅乱统计的脏记录 */
    @Test
    void addDownloads_shouldRejectNullIid() {
        Result<Boolean> result = holder.addDownloads("u1", model(null));

        assertFalse(Boolean.TRUE.equals(result.getDatas()));
        verify(modelsDownloadsBeanHolder, never()).install(any(ModelsDownloadsBean.class));
    }

    /**
     * 周下载量必须按模型过滤。
     * 修复前返回全站总量，前端每个模型都显示同一个数字。
     */
    @Test
    void queryWeeklyDownloads_shouldFilterByModel() {
        when(modelsDownloadsBeanMapper.count(anyString())).thenReturn(3);

        Result<Integer> result = holder.queryWeeklyDownloads(model(7L));

        assertNotNull(result.getDatas());
        assertEquals(3, result.getDatas().intValue());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(modelsDownloadsBeanMapper).count(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("createtime"), "仍需保留本周时间条件");
        assertTrue(sql.contains("moudelsiid"), "必须补上模型条件");
        assertTrue(sql.contains("7"), "必须带上具体的模型 id");
    }

    /** 模型 id 为空必须拒绝，不能退化成查全站 */
    @Test
    void queryWeeklyDownloads_shouldRejectNullIid() {
        Result<Integer> result = holder.queryWeeklyDownloads(model(null));

        assertFalse(result.getDatas() != null);
        verify(modelsDownloadsBeanMapper, never()).count(anyString());
    }
}
