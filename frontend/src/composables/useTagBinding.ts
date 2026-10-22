import { tagsBindApi } from '@/api/tagsbind'

/**
 * 标签绑定刷新的统一实现。
 *
 * 背景：这个逻辑原本在 6 个页面各写了一份，代码几乎完全相同 ——
 *   能力广场 CapabilitiesView、模型仓库 ModelsView、数据集 DatasetView、
 *   场景应用 ApplicationsView、知识库广场 KnowledgeBaseSquareView、知识库中心 KnowledgeBaseView。
 * 差别只有三处：请求的 type 值、数据源数组、数据项里当 key 的字段名（id / iid）。
 *
 * 抽成 composable 的收益：
 *   - 改一次逻辑，6 个页面同时生效（原来要翻 6 个文件）
 *   - 顺带统一了错误处理：原来只有 KnowledgeBaseSquareView 有 try/catch，
 *     其余 5 处请求失败会直接把异常抛给调用方
 *
 * 用法：
 *   const { refreshTagBind } = useTagBinding(() => state.dataList, { type: 'models' })
 *   await refreshTagBind()      // 刷新全部
 *   await refreshTagBind(id)    // 只刷新指定项
 */

interface UseTagBindingOptions {
  /** 标签绑定的业务类型，对应 tagsBindApi 请求体里的 data.type */
  type: string
  /** 数据项里作为标签 key 的字段名，默认 'iid'（知识库类页面传 'id'） */
  idKey?: string
}

export function useTagBinding<T extends Record<string, any> & { tagList?: unknown[] }>(
  getItems: () => T[],
  options: UseTagBindingOptions
) {
  const { type, idKey = 'iid' } = options

  /**
   * @param id 传了则只刷新该项；不传则刷新整个列表
   */
  const refreshTagBind = async (id?: string): Promise<void> => {
    try {
      const { data: res } = await tagsBindApi.query({ data: { type } })
      if (res?.resp_code != 0) return;

      // hash -> 标签列表 的映射
      const map: Record<string, unknown[]> = {};
      for (const bind of res.datas || []) {
        map[bind.hash] = bind.lableBeans || [];
      }

      const items = getItems();
      if (id) {
        const item = items.find((k) => String(k[idKey]) === id);
        if (item) item.tagList = map[id] || [];
      } else {
        items.forEach((item) => {
          item.tagList = map[String(item[idKey])] || [];
        });
      }
    } catch (err) {
      // 统一兜底：标签绑定失败不影响主流程，但要留下痕迹
      console.error('刷新标签绑定失败:', err);
    }
  };

  return { refreshTagBind };
}
