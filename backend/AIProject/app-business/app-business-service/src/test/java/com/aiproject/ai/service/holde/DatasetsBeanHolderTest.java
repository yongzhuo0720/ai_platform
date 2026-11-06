package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.DatasetsBean;
import com.aiproject.ai.service.mapper.DatasetsBeanMapper;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据集目录的全局搜索测试：公开目录只做关键词过滤，但必须限制扫描量与返回条数。
 */
@ExtendWith(MockitoExtension.class)
class DatasetsBeanHolderTest {

    @Mock
    DatasetsBeanMapper<DatasetsBean> datasetsBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    DatasetsBeanHolder holder;

    @Test
    void search_shouldMatchTypeAndCapResults() {
        DatasetsBean byName = new DatasetsBean();
        byName.setName("制度问答集");
        DatasetsBean byType = new DatasetsBean();
        byType.setName("通用集");
        byType.setType("文本");
        DatasetsBean noMatch = new DatasetsBean();
        noMatch.setName("语音集");
        noMatch.setType("音频");
        when(datasetsBeanMapper.select(any(PageRequest.class), any(DatasetsBean.class)))
                .thenReturn(Arrays.asList(byName, byType, noMatch));

        List<DatasetsBean> byNameResult = holder.search("制度", 6);
        assertEquals(1, byNameResult.size());
        assertEquals("制度问答集", byNameResult.get(0).getName());

        List<DatasetsBean> byTypeResult = holder.search("文本", 1);
        assertEquals(1, byTypeResult.size());
        assertEquals("通用集", byTypeResult.get(0).getName());
    }

    @Test
    void search_blankKeyword_shouldNotTouchDb() {
        assertTrue(holder.search("  ", 6).isEmpty());
        assertTrue(holder.search("制度", 0).isEmpty());
        verify(datasetsBeanMapper, never()).select(any(PageRequest.class), any(DatasetsBean.class));
    }
}
