<template>
  <div class="login-screen" :class="{ 'is-admin-mode': isAdmin, 'is-animating': isAnimating }">
    <div class="login-bg-layer"></div>
    <div class="login-gradient-overlay"></div>

    <div class="login-orb orb-1"></div>
    <div class="login-orb orb-2"></div>
    <div class="login-orb orb-3"></div>
    <div class="login-orb orb-4"></div>

    <svg class="liquid-svg-filter" width="0" height="0">
      <defs>
        <filter id="liquid-goo">
          <feGaussianBlur in="SourceGraphic" stdDeviation="12" result="blur" />
          <feColorMatrix
            in="blur"
            mode="matrix"
            values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 28 -11"
            result="goo"
          />
          <feComposite in="SourceGraphic" in2="goo" operator="atop" />
        </filter>
      </defs>
    </svg>

    <div class="login-card">
      <div class="login-card-decos">
        <div class="card-deco deco-a"></div>
        <div class="card-deco deco-b"></div>
        <div class="card-deco deco-c"></div>
        <div class="card-deco deco-d"></div>
      </div>

      <div class="login-panels">
        <!-- 左侧：用户平台 -->
        <div class="login-panel panel-user">
          <div class="panel-content">
            <div class="panel-logo">
                <img src="@/assets/images/brand-logo.svg" alt="logo" class="logo-image" />
            </div>
            <div class="panel-title">AI 聚合平台</div>
            <p class="panel-desc">统一智能工作台，汇聚多模型 AI 能力</p>

            <form class="login-form" @submit.prevent="handleLogin">
              <div class="login-field">
                <label>用户名</label>
                <input
                  v-model="form.uaccess"
                  type="text"
                  aria-label="平台用户名"
                  autocomplete="username"
                  class="input-field"
                  placeholder="请输入用户名"
                />
              </div>

              <div class="login-field">
                <label>密码</label>
                <input
                  v-model="form.upwd"
                  type="password"
                  aria-label="平台密码"
                  autocomplete="current-password"
                  class="input-field"
                  placeholder="请输入密码"
                />
              </div>

              <button type="submit" class="login-btn" :disabled="loading">
                <Lock v-if="!loading" class="login-btn-icon" />
                {{ loading ? '登录中...' : '进入智能平台' }}
              </button>
            </form>
          </div>
        </div>

        <!-- 右侧：管理后台 -->
        <div class="login-panel panel-admin">
          <div class="panel-content">
            <div class="panel-logo ">
               <img src="@/assets/images/brand-logo.svg" alt="logo" class="logo-image" />
            </div>
            <div class="panel-title">系统管理后台</div>
            <p class="panel-desc">统一智能工作台 · 系统管理中心</p>

            <form class="login-form" @submit.prevent="handleLogin">
              <div class="login-field">
                <label>用户名</label>
                <input
                  v-model="form.uaccess"
                  type="text"
                  aria-label="管理后台用户名"
                  autocomplete="username"
                  class="input-field"
                  placeholder="请输入用户名"
                />
              </div>

              <div class="login-field">
                <label>密码</label>
                <input
                  v-model="form.upwd"
                  type="password"
                  aria-label="管理后台密码"
                  autocomplete="current-password"
                  class="input-field"
                  placeholder="请输入密码"
                />
              </div>

              <button type="submit" class="login-btn admin" :disabled="loading">
                <Lock v-if="!loading" class="login-btn-icon" />
                {{ loading ? '登录中...' : '进入管理后台' }}
              </button>
            </form>
          </div>
        </div>
      </div>

      <!-- 滑动遮罩 -->
      <div
        class="login-slider"
        :class="{
          'is-right': !isAdmin,
          'to-right': isAnimating && !isAdmin,
          'to-left': isAnimating && isAdmin
        }"
      >
        <div class="login-slider-liquid">
          <div class="login-slider-shell"></div>
        </div>

        <div class="slider-content">
          <Transition name="slider-text" mode="out-in">
            <div v-if="!isAdmin" key="user-cta" class="slider-cta">
              <div class="slider-icon">
                <Monitor />
              </div>
              <h3>AI 聚合平台</h3>
              <p>模型对话、知识库、能力广场与开发工具</p>
              <button type="button" class="slider-btn" @click="switchMode(true)">
                前往管理后台
              </button>
            </div>

            <div v-else key="admin-cta" class="slider-cta">
              <div class="slider-icon">
                <Setting />
              </div>
              <h3>系统管理后台</h3>
              <p>权限、用户、日志与系统配置一站式管理</p>
              <button type="button" class="slider-btn" @click="switchMode(false)">
                前往智能平台
              </button>
            </div>
          </Transition>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Lock, Setting, Monitor } from '@element-plus/icons-vue'
import { useIndex } from './index'

const { loading, isAdmin, isAnimating, form, handleLogin, switchMode } = useIndex()
</script>

<script lang="ts">
export default {
  name: 'LoginView'
}
</script>

<style scoped>
@import './index.less';

.login-screen {
  background: #edf2f8;
  min-height: 100dvh;
  overflow-y: auto;
}

.login-bg-layer {
  display: none;
}

.login-gradient-overlay {
  background: transparent;
}

.login-orb,
.login-card-decos {
  display: none;
}

.login-card {
  background: #ffffff;
  border: 1px solid #dce4ef;
  border-radius: 16px;
  backdrop-filter: none;
  box-shadow: 0 16px 48px rgba(21, 50, 90, 0.1);
}

.login-slider-liquid {
  filter: none;
}

.login-slider .login-slider-shell,
.login-slider.is-right .login-slider-shell {
  background: #183e70;
  border: none;
  border-radius: 0;
  box-shadow: none;
}

.login-panel.panel-user .login-field .input-field,
.login-panel.panel-admin .login-field .input-field {
  border: 1px solid #cbd7e6;
  border-radius: 8px;
  background: #f8fafc;
  color: #20344f;
}

.login-btn {
  border-radius: 8px;
  box-shadow: none;
}

@media (max-width: 480px) {
  .login-screen {
    padding: 16px;
    align-items: flex-start;
  }
}

@media (max-width: 768px) {
  .login-slider {
    height: auto;
    background: #183e70;
  }
}
</style>

<style>
.slider-text-enter-active,
.slider-text-leave-active {
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.slider-text-enter-from {
  opacity: 0;
  transform: translateY(12px);
}

.slider-text-leave-to {
  opacity: 0;
  transform: translateY(-12px);
}
</style>
