import { computed, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import aiApiTokenApi, { type AiApiToken } from '@/api/aiApiToken'
import { debug } from '@/utils/debug'

/** 0 表示不过期；编辑时选 0 不会下发清空（框架的 update 只写非 null 字段） */
const EXPIRE_OPTIONS = [
  { label: '不过期', days: 0 },
  { label: '30 天', days: 30 },
  { label: '90 天', days: 90 },
  { label: '365 天', days: 365 },
]

const DAY_MS = 24 * 60 * 60 * 1000

function expireAtOf(days: number): number | null {
  return days > 0 ? Date.now() + days * DAY_MS : null
}

export function useIndex() {
  const state = reactive({
    rows: [] as AiApiToken[],
    loading: false,
    saving: false,
    formVisible: false,
    formMode: 'create' as 'create' | 'edit',
    form: {
      iid: 0,
      name: '',
      allowModels: '',
      expireDays: 0,
      enable: true,
    },
    /** 明文只在这里停留，弹窗一关闭就抹掉 */
    issued: '',
    issuedName: '',
  })

  const activeCount = computed(() => state.rows.filter((row) => isUsable(row)).length)

  onMounted(() => {
    load()
  })

  async function load() {
    state.loading = true
    try {
      const { data: res } = await aiApiTokenApi.list()
      if (res.resp_code === 0) {
        state.rows = res.datas ?? []
      } else {
        ElMessage.error(res.resp_msg || '获取密钥列表失败')
      }
    } catch (err) {
      debug.error('获取密钥列表失败', err)
      ElMessage.error('获取密钥列表失败，请稍后重试')
    } finally {
      state.loading = false
    }
  }

  function openCreate() {
    state.formMode = 'create'
    state.form = { iid: 0, name: '', allowModels: '', expireDays: 0, enable: true }
    state.formVisible = true
  }

  function openEdit(row: AiApiToken) {
    state.formMode = 'edit'
    state.form = {
      iid: row.iid,
      name: row.name,
      allowModels: row.allowModels ?? '',
      expireDays: 0,
      enable: row.enable !== false,
    }
    state.formVisible = true
  }

  async function save() {
    const name = state.form.name.trim()
    if (!name) {
      ElMessage.warning('请填写密钥名称')
      return
    }
    state.saving = true
    try {
      if (state.formMode === 'create') {
        const { data: res } = await aiApiTokenApi.create({
          name,
          allowModels: state.form.allowModels.trim(),
          expireAt: expireAtOf(state.form.expireDays),
        })
        if (res.resp_code !== 0) {
          ElMessage.error(res.resp_msg || '创建失败')
          return
        }
        state.formVisible = false
        // 明文只有这一次机会，错过就只能重建
        state.issuedName = name
        state.issued = res.datas.plaintext
        await load()
        return
      }
      const payload: {
        iid: number;
        name: string;
        allowModels: string;
        enable: boolean;
        expireAt?: number | null;
      } = {
        iid: state.form.iid,
        name,
        allowModels: state.form.allowModels.trim(),
        enable: state.form.enable,
      }
      if (state.form.expireDays > 0) payload.expireAt = expireAtOf(state.form.expireDays)
      const { data: res } = await aiApiTokenApi.update(payload)
      if (res.resp_code !== 0) {
        ElMessage.error(res.resp_msg || '保存失败')
        return
      }
      ElMessage.success('已保存')
      state.formVisible = false
      await load()
    } catch (err) {
      debug.error('保存密钥失败', err)
      ElMessage.error('保存失败，请稍后重试')
    } finally {
      state.saving = false
    }
  }

  async function toggleEnable(row: AiApiToken, enable: boolean) {
    // 开关是受控的（:model-value 由 row.enable 推导），不回写 row 就永远不动
    row.enable = enable
    try {
      const { data: res } = await aiApiTokenApi.update({ iid: row.iid, enable })
      if (res.resp_code !== 0) {
        // 失败要退回去，否则界面上显示的状态和库里不一致
        row.enable = !enable
        ElMessage.error(res.resp_msg || '操作失败')
        return
      }
      ElMessage.success(enable ? '已启用' : '已停用，调用会立即返回 401')
    } catch (err) {
      row.enable = !enable
      debug.error('切换密钥状态失败', err)
      ElMessage.error('操作失败，请稍后重试')
    }
  }

  async function remove(row: AiApiToken) {
    try {
      await ElMessageBox.confirm(
        `删除「${row.name}」后，用它签发的程序会立刻收到 401，且无法恢复。`,
        '确认删除密钥',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      const { data: res } = await aiApiTokenApi.remove(row.iid)
      if (res.resp_code !== 0) {
        ElMessage.error(res.resp_msg || '删除失败')
        return
      }
      ElMessage.success('已删除')
      await load()
    } catch (err) {
      debug.error('删除密钥失败', err)
      ElMessage.error('删除失败，请稍后重试')
    }
  }

  function closeIssued() {
    state.issued = ''
    state.issuedName = ''
  }

  async function copyIssued() {
    const text = state.issued
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
      ElMessage.success('已复制，之后无法再查看')
    } catch {
      // 非安全上下文（http://内网 IP）没有 clipboard API
      ElMessage.warning('当前环境不支持自动复制，请手动选中文本复制')
    }
  }

  function isExpired(row: AiApiToken): boolean {
    return row.expireAt != null && row.expireAt < Date.now()
  }

  function isUsable(row: AiApiToken): boolean {
    return row.enable !== false && !isExpired(row)
  }

  function statusLabel(row: AiApiToken): string {
    if (isExpired(row)) return '已过期'
    return row.enable === false ? '已停用' : '可用'
  }

  function statusTagType(row: AiApiToken): 'success' | 'danger' | 'info' {
    if (isUsable(row)) return 'success'
    return isExpired(row) ? 'info' : 'danger'
  }

  function expireLabel(row: AiApiToken): string {
    if (row.expireAt == null) return '不过期'
    return formatTime(row.expireAt)
  }

  function allowedLabel(row: AiApiToken): string {
    const raw = (row.allowModels ?? '').trim()
    return raw ? raw : '全部模型'
  }

  function formatTime(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') return '-'
    const date = typeof value === 'number' ? new Date(value) : new Date(String(value).replace(/\//g, '-').replace(' ', 'T'))
    if (Number.isNaN(date.getTime())) return String(value)
    const pad = (num: number) => String(num).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  }

  /** 给用户的可复制示例：模型名取 /v1/models 里的 id */
  const usageSample = computed(() => {
    const base = window.location.origin
    return [
      `curl ${base}/ai/v1/chat/completions \\`,
      `  -H "Content-Type: application/json" \\`,
      `  -H "Authorization: Bearer ${state.issued || '<你的密钥>'}" \\`,
      `  -d '{"model":"<模型 id>","messages":[{"role":"user","content":"say hi"}]}'`,
    ].join('\n')
  })

  return {
    EXPIRE_OPTIONS,
    state,
    activeCount,
    load,
    openCreate,
    openEdit,
    save,
    toggleEnable,
    remove,
    closeIssued,
    copyIssued,
    isExpired,
    isUsable,
    statusLabel,
    statusTagType,
    expireLabel,
    allowedLabel,
    formatTime,
    usageSample,
  }
}
