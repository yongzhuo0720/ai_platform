<script setup lang="ts">
import { Close, Avatar } from "@element-plus/icons-vue";
import {
  assistantDialogVisible,
  assistantForm,
  isAssistantEditing,
  assistantTemplates,
  canConfirmAssistant,
  closeAssistantDialog,
  applyAssistantTemplate,
  assistantDeleteConfirm,
  cancelDeleteAssistant,
  confirmDeleteAssistant,
} from "./chatAssistantState";
import { confirmAssistantDialog } from "./useChatConversations";
</script>

<template>
  <!-- ============== 添加助手弹窗 ============== -->
  <Teleport to="body">
    <transition name="chat-assistant-dialog-fade">
      <div
        v-if="assistantDialogVisible"
        class="chat-assistant-dialog-overlay"
        @click.self="closeAssistantDialog"
      >
        <div
          class="chat-assistant-dialog"
          role="dialog"
          aria-modal="true"
          :aria-label="isAssistantEditing ? '更新助手' : '新建助手'"
        >
          <header class="chat-assistant-dialog-header">
            <div class="chat-assistant-dialog-title">
              <h3>{{ isAssistantEditing ? '更新助手' : '新建助手' }}</h3>
            </div>
            <button
              type="button"
              class="chat-assistant-dialog-close"
              aria-label="关闭"
              @click="closeAssistantDialog"
            >
              <el-icon><Close /></el-icon>
            </button>
          </header>

          <div class="chat-assistant-dialog-body">
            <div class="chat-assistant-field">
              <label class="chat-assistant-label" for="chat-assistant-name">助手名称</label>
              <div class="chat-assistant-input-wrap">
                <el-icon class="chat-assistant-input-icon"><Avatar /></el-icon>
                <input
                  id="chat-assistant-name"
                  v-model="assistantForm.name"
                  type="text"
                  class="chat-assistant-input"
                  placeholder="例如：商业数据分析"
                  maxlength="32"
                  @keydown.enter.prevent="confirmAssistantDialog"
                />
              </div>
            </div>

            <div class="chat-assistant-field">
              <label class="chat-assistant-label" for="chat-assistant-desc">提示词</label>
              <textarea
                id="chat-assistant-desc"
                v-model="assistantForm.description"
                class="chat-assistant-textarea"
                placeholder="描述助手角色与行为规范，将作为系统提示词发送给模型"
                rows="6"
              />
            </div>

            <div class="chat-assistant-quick">
              <p class="chat-assistant-quick-title">常用助手模板</p>
              <div class="chat-assistant-quick-list">
                <button
                  v-for="template in assistantTemplates"
                  :key="template.name"
                  type="button"
                  class="chat-assistant-quick-chip"
                  @click="applyAssistantTemplate(template)"
                >
                  <span class="chat-assistant-quick-name">{{ template.name }}</span>
                  <span class="chat-assistant-quick-desc">{{ template.description }}</span>
                </button>
              </div>
            </div>
          </div>

          <footer class="chat-assistant-dialog-footer">
            <button
              type="button"
              class="chat-assistant-btn chat-assistant-btn-ghost"
              @click="closeAssistantDialog"
            >
              取消
            </button>
            <button
              type="button"
              class="chat-assistant-btn chat-assistant-btn-primary"
              :disabled="!canConfirmAssistant"
              @click="confirmAssistantDialog"
            >
              {{ isAssistantEditing ? '更新' : '添加' }}
            </button>
          </footer>
        </div>
      </div>
    </transition>
  </Teleport>

  <!-- ============== 删除助手二次确认弹窗 ============== -->
  <Teleport to="body">
    <transition name="chat-assistant-dialog-fade">
      <div
        v-if="assistantDeleteConfirm"
        class="chat-assistant-dialog-overlay"
        @click.self="cancelDeleteAssistant"
      >
        <div
          class="chat-assistant-dialog chat-assistant-confirm-dialog"
          role="alertdialog"
          aria-modal="true"
          :aria-label="isAssistantEditing ? '更新助手' : '新建助手'"
        >
          <header class="chat-assistant-dialog-header">
            <div class="chat-assistant-dialog-title">
              <span class="chat-assistant-confirm-mark">!</span>
              <h3>删除助手</h3>
            </div>
          </header>
          <div class="chat-assistant-dialog-body">
            <p class="chat-assistant-confirm-text">
              确认删除助手「<strong>{{ assistantDeleteConfirm.name }}</strong>」吗？删除后将不再出现在当前侧栏列表中，且无法撤销。
            </p>
          </div>
          <footer class="chat-assistant-dialog-footer">
            <button
              type="button"
              class="chat-assistant-btn chat-assistant-btn-ghost"
              @click="cancelDeleteAssistant"
            >
              取消
            </button>
            <button
              type="button"
              class="chat-assistant-btn chat-assistant-btn-danger"
              @click="confirmDeleteAssistant"
            >
              删除
            </button>
          </footer>
        </div>
      </div>
    </transition>
  </Teleport>
</template>
