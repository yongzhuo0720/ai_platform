<template>
    <Teleport to="body">
        <Transition name="tag-dialog-fade">
            <div v-if="visible" class="tag-manage-dialog-overlay" @click="handleOverlayClick">
                <div class="tag-manage-dialog" @click.stop>
                    <!-- 头部 -->
                    <div class="tag-dialog-header">
                        <h3 class="tag-dialog-title">标签管理</h3>
                        <button type="button" class="tag-dialog-close" @click="close">
                            <Close />
                        </button>
                    </div>

                    <!-- 内容区 -->
                    <div class="tag-dialog-body">
                        <!-- 新增标签 -->
                        <div class="tag-add-section">
                            <div class="tag-input-wrapper">
                                <input v-model="newTagName" type="text" placeholder="输入新标签名称" class="tag-input"
                                    @keyup.enter="handleAddTag" />
                                <button type="button" class="tag-add-confirm-btn" :disabled="!newTagName.trim()"
                                    @click="handleAddTag">
                                    <Plus />
                                    <span>新增</span>
                                </button>
                            </div>
                        </div>

                        <!-- 标签列表 -->
                        <div class="tag-list-section">
                            <div v-if="localTags.length === 0" class="tag-list-empty">
                                <FolderOpened />
                                <p>暂无标签，请添加</p>
                            </div>
                            <div v-else class="tag-list">
                                <div v-for="tag in localTags" :key="tag.iid" class="tag-item"
                                    :class="{ 'is-editing': editingTagId === tag.iid }">
                                    <!-- 正常显示模式 -->
                                    <template v-if="editingTagId !== tag.iid">
                                        <span class="tag-item-name">{{ tag.name }}</span>
                                        <span class="tag-item-count">{{ tag.count || 0 }}</span>
                                        <div class="tag-item-actions">
                                            <button type="button" class="tag-item-btn edit" @click="startEdit(tag)"
                                                title="编辑">
                                                <Edit />
                                            </button>
                                            <button type="button" class="tag-item-btn delete" @click="handleDelete(tag)"
                                                title="删除">
                                                <Delete />
                                            </button>
                                        </div>
                                    </template>

                                    <!-- 编辑模式 -->
                                    <template v-else>
                                        <div class="tag-edit-wrapper">
                                            <input v-model="editingTagName" type="text" class="tag-edit-input"
                                                @keyup.enter="confirmEdit" @keyup.esc="cancelEdit" ref="editInputRef" />
                                            <div class="tag-edit-actions">
                                                <button type="button" class="tag-edit-btn confirm" @click="confirmEdit">
                                                    <Check />
                                                </button>
                                                <button type="button" class="tag-edit-btn cancel" @click="cancelEdit">
                                                    <Close />
                                                </button>
                                            </div>
                                        </div>
                                    </template>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- 底部 -->
                    <div class="tag-dialog-footer">
                        <button type="button" class="tag-btn-secondary" @click="close">
                            关闭
                        </button>
                    </div>
                </div>
            </div>
        </Transition>
    </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Plus, Close, Edit, Delete, Check, FolderOpened } from '@element-plus/icons-vue'
import { tagsApi } from '@/api/tags'


const props = defineProps(['type','visible','tags'])
const emit = defineEmits(['refresh','update:visible'])


const localTags = ref<any[]>([])
const newTagName = ref('')
const editingTagId = ref<number | null>(null)
const editingTagName = ref('')
const editInputRef = ref<HTMLInputElement>()

// 监听visible和tags变化
watch(() => props.visible, (val) => {
    if (val) {
        localTags.value = [...props.tags]
        newTagName.value = ''
        editingTagId.value = null
    }
})

watch(() => props.tags, (val) => {
    localTags.value = [...val]
}, { deep: true })

// 关闭弹窗
const close = () => {
    emit('update:visible', false)
}

// 点击遮罩关闭
const handleOverlayClick = () => {
    close()
}

// 新增标签
const handleAddTag = async () => {
    const name = newTagName.value.trim()
    if (!name) return
    let reqdata = {
        data: {
            name,
            type: props.type,
        }
    }

    try {
        const { data: res } = await tagsApi.create(reqdata)
        if (res.resp_code == 0) {
            newTagName.value = ''
            emit('refresh')
            ElMessage.success('标签创建成功')
        }
    } catch (error) {
        console.error('创建标签失败:', error)
        ElMessage.error('创建标签失败')
    }
}

// 开始编辑
const startEdit = (tag: any) => {
    editingTagId.value = tag.iid;
    editingTagName.value = tag.name;
    nextTick(() => {
        editInputRef.value?.focus();
    })
}

// 确认编辑
const confirmEdit = async () => {
    if (!editingTagId.value) return
    const name = editingTagName.value.trim()
    if (!name) {
        ElMessage.warning('标签名称不能为空')
        return
    }

    try {
        let reqdata = {
            data: {
                name,
            },
            where: {
                iid: editingTagId.value,
                type: props.type,
            }
        }
        const { data: res } = await tagsApi.update(reqdata)
        if (res.resp_code == 0) {
            const tag = localTags.value.find(t => t.iid === editingTagId.value)
            if (tag) {
                tag.name = name
            }
            editingTagId.value = null
            emit('refresh', [...localTags.value])
            ElMessage.success('标签修改成功')
        }
    } catch (error) {
        console.error('修改标签失败:', error)
        ElMessage.error('修改标签失败')
    }
}

// 取消编辑
const cancelEdit = () => {
    editingTagId.value = null
    editingTagName.value = ''
}

// 删除标签
const handleDelete = (tag: any) => {
    ElMessageBox.confirm(
        `确定要删除标签 "${tag.name}" 吗？删除后该标签将从所有知识库中移除。`,
        '确认删除',
        {
            confirmButtonText: '删除',
            cancelButtonText: '取消',
            type: 'warning',
        }
    ).then(async () => {
        try {
            let reqdata = {
                data: {
                    iid: tag.iid,
                    type: props.type,
                }
            }
            const { data: res } = await tagsApi.delete(reqdata)
            if (res.resp_code == 0) {
                emit('refresh')
                ElMessage.success('标签删除成功')
            }
        } catch (error) {
            console.error('删除标签失败:', error)
            ElMessage.error('删除标签失败')
        }
    }).catch(() => {
        // 取消删除
    })
}
</script>

<style scoped>
.tag-manage-dialog-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 5, 30, 0.7);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 2000;
    backdrop-filter: blur(4px);
}

.tag-manage-dialog {
    width: 600px;
    max-height: 80vh;
    background: var(--glass-bg1);
    border: 1px solid var(--glass-border);
    border-radius: 12px;
    box-shadow: var(--glass-shadow);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    backdrop-filter: var(--glass-blur);
    -webkit-backdrop-filter: var(--glass-blur);
}

.tag-dialog-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 20px 24px;
    border-bottom: 1px solid var(--border-color-soft);
}

.tag-dialog-title {
    font-size: 18px;
    font-weight: 700;
    color: var(--text-primary);
    margin: 0;
    font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
    letter-spacing: 0.01em;
}

.tag-dialog-close {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    background: transparent;
    border: none;
    border-radius: 8px;
    cursor: pointer;
    color: var(--text-tertiary);
    transition: all 0.2s;
}

.tag-dialog-close:hover {
    background: var(--accent-soft);
    color: var(--text-primary);
}

.tag-dialog-close svg {
    width: 16px;
    height: 16px;
}

.tag-dialog-body {
    flex: 1;
    overflow-y: auto;
    padding: 24px;
}

.tag-add-section {
    margin-bottom: 20px;
}

.tag-input-wrapper {
    display: flex;
    gap: 8px;
}

.tag-input {
    flex: 1;
    padding: 8px 12px;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 8px;
    font-size: 14px;
    color: var(--text-primary);
    outline: none;
    transition: border-color 0.2s;
}

.tag-input::placeholder {
    color: var(--text-tertiary);
}

.tag-input:focus {
    border-color: var(--accent);
    background: var(--bg-primary);
}

.tag-add-confirm-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 20px;
    background: linear-gradient(135deg, var(--accent) 0%, var(--brand-blue-700) 100%);
    color: white;
    border: none;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.3s ease;
    white-space: nowrap;
    box-shadow: 0 4px 12px var(--accent-soft);
    position: relative;
    overflow: hidden;
}

.tag-add-confirm-btn:hover:not(:disabled) {
    box-shadow: 0 6px 16px var(--accent-soft);
    transform: translateY(-1px);
    filter: brightness(1.1);
}

.tag-add-confirm-btn:active:not(:disabled) {
    transform: translateY(0);
    box-shadow: 0 2px 8px var(--accent-soft);
}

.tag-add-confirm-btn:disabled {
    background: var(--border-color);
    box-shadow: none;
    cursor: not-allowed;
    opacity: 0.6;
    color: var(--text-tertiary);
}

.tag-add-confirm-btn svg {
    width: 14px;
    height: 14px;
}

.tag-list-section {
    min-height: 200px;
}

.tag-list-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 40px;
    color: var(--text-tertiary);
}

.tag-list-empty svg {
    width: 48px;
    height: 48px;
    margin-bottom: 12px;
    color: var(--border-color);
}

.tag-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.tag-item {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    background: var(--accent-soft);
    border-radius: 6px;
    transition: background 0.2s;
}

.tag-item:hover {
    background: var(--border-color);
}

.tag-item.is-editing {
    background: var(--accent-soft);
    outline: 1px solid var(--accent);
    flex: 0 0 auto;
    min-width: 140px;
}

.tag-item-name {
    font-size: 13px;
    color: var(--text-primary);
}

.tag-item-count {
    font-size: 12px;
    color: var(--text-tertiary);
}

.tag-item-actions {
    display: flex;
    gap: 4px;
}

.tag-item-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    background: transparent;
    border: none;
    border-radius: 4px;
    cursor: pointer;
    color: var(--text-tertiary);
    transition: all 0.2s;
}

.tag-item-btn:hover {
    background: var(--accent-soft);
    color: var(--accent);
}

.tag-item-btn.delete:hover {
    background: rgba(255, 77, 79, 0.25);
    color: #ff4d4f;
}

.tag-item-btn svg {
    width: 14px;
    height: 14px;
}

.tag-edit-wrapper {
    display: flex;
    align-items: center;
    gap: 8px;
    flex: 1;
}

.tag-edit-input {
    width: 100px;
    padding: 2px 5px;
    background: var(--bg-primary);
    border: 1px solid var(--accent);
    border-radius: 4px;
    font-size: 13px;
    color: var(--text-primary);
    outline: none;
}

.tag-edit-actions {
    display: flex;
    gap: 6px;
}

.tag-edit-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    transition: all 0.2s;
}

.tag-edit-btn.confirm {
    background: var(--success);
    color: white;
}

.tag-edit-btn.confirm:hover {
    filter: brightness(1.15);
}

.tag-edit-btn.cancel {
    background: var(--border-color);
    color: var(--text-secondary);
}

.tag-edit-btn.cancel:hover {
    background: var(--border-color-strong);
    color: var(--text-primary);
}

.tag-edit-btn svg {
    width: 14px;
    height: 14px;
}

.tag-dialog-footer {
    display: flex;
    justify-content: flex-end;
    padding: 16px 24px;
    border-top: 1px solid var(--border-color-soft);
    gap: 12px;
}

.tag-btn-secondary {
    padding: 10px 20px;
    background: var(--border-color-soft);
    color: var(--text-secondary);
    border: none;
    border-radius: 8px;
    font-size: 14px;
    cursor: pointer;
    transition: all 0.2s;
}

.tag-btn-secondary:hover {
    background: var(--border-color);
    color: var(--text-primary);
}

/* 动画 */
.tag-dialog-fade-enter-active,
.tag-dialog-fade-leave-active {
    transition: opacity 0.3s;
}

.tag-dialog-fade-enter-from,
.tag-dialog-fade-leave-to {
    opacity: 0;
}

.tag-dialog-fade-enter-active .tag-manage-dialog,
.tag-dialog-fade-leave-active .tag-manage-dialog {
    transition: transform 0.3s, opacity 0.3s;
}

.tag-dialog-fade-enter-from .tag-manage-dialog,
.tag-dialog-fade-leave-to .tag-manage-dialog {
    transform: scale(0.95);
    opacity: 0;
}

/* 浅色模式微调 */
html[data-theme="light"] .tag-manage-dialog-overlay {
    background: rgba(0, 5, 30, 0.3);
}

html[data-theme="light"] .tag-edit-btn.confirm {
    background: var(--success);
}

html[data-theme="light"] .tag-edit-btn.confirm:hover {
    filter: brightness(1.1);
}
</style>
